package ch.epfl.bluebrain.nexus.delta.plugins.search

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.projectionIndex
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.ElasticSearchProjection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeView, CompositeViewSearchParams}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchRejection.WrappedElasticSearchClientError
import ch.epfl.bluebrain.nexus.delta.plugins.search.model._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress.{Project => ProjectAcl}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination
import io.circe.{Json, JsonObject}
import monix.bio.{IO, UIO}

trait Search {

  /**
    * Queries the underlying elasticsearch search indices that the ''caller'' has access to
    *
    * @param payload
    *   the query payload
    */
  def query(payload: JsonObject, qp: Uri.Query)(implicit caller: Caller): IO[SearchRejection, Json]
}

object Search {

  final case class TargetProjection(projection: ElasticSearchProjection, view: CompositeView, rev: Int)

  private[search] type ListProjections = () => UIO[Seq[TargetProjection]]

  /**
    * Constructs a new [[Search]] instance.
    */
  final def apply(
      compositeViews: CompositeViews,
      aclCheck: AclCheck,
      client: ElasticSearchClient,
      prefix: String
  ): Search = {

    val listProjections: ListProjections = () =>
      compositeViews
        .list(
          Pagination.OnePage,
          CompositeViewSearchParams(deprecated = Some(false), filter = v => UIO.pure(v.id == defaultViewId)),
          Ordering.by(_.createdAt)
        )
        .map(
          _.results
            .flatMap { entry =>
              val res = entry.source
              for {
                projection   <- res.value.projections.value.find(_.id == defaultProjectionId)
                esProjection <- projection.asElasticSearch
              } yield TargetProjection(esProjection, res.value, res.rev)
            }
        )
    apply(listProjections, aclCheck, client, prefix)
  }

  /**
    * Constructs a new [[Search]] instance.
    */
  final def apply(
      listProjections: ListProjections,
      aclCheck: AclCheck,
      client: ElasticSearchClient,
      prefix: String
  ): Search =
    new Search {
      override def query(payload: JsonObject, qp: Uri.Query)(implicit caller: Caller): IO[SearchRejection, Json] = {
        for {
          allProjections    <- listProjections()
          accessibleIndices <- aclCheck.mapFilter[TargetProjection, String](
                                 allProjections,
                                 p => ProjectAcl(p.view.project) -> p.projection.permission,
                                 p => projectionIndex(p.projection, p.view.uuid, p.rev, prefix).value
                               )
          results           <- client.search(payload, accessibleIndices, qp)().mapError(WrappedElasticSearchClientError)
        } yield results
      }
    }
}
