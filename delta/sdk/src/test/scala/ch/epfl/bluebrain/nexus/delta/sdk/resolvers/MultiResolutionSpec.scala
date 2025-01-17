package ch.epfl.bluebrain.nexus.delta.sdk.resolvers

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ResolverResolutionGen, ResourceGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegmentRef, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution.Fetch
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverRejection.{InvalidResolution, InvalidResolverId, InvalidResolverResolution, ProjectContextRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverResolutionRejection.ResourceNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResourceResolutionReport.ResolverReport
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{MultiResolutionResult, ResolverRejection, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.Fixtures
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.{Latest, Revision}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestHelpers}
import io.circe.Json
import monix.bio.IO
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class MultiResolutionSpec
    extends AnyWordSpecLike
    with Matchers
    with TestHelpers
    with IOValues
    with CirceLiteral
    with Fixtures {

  private val alice                = User("alice", Label.unsafe("wonderland"))
  implicit val aliceCaller: Caller = Caller(User("alice", Label.unsafe("wonderland")), Set(alice))

  private val projectRef = ProjectRef.unsafe("org", "project")

  private val resourceId = nxv + "resource"
  private val resource   =
    ResourceGen.resource(resourceId, projectRef, jsonContentOf("resources/resource.json", "id" -> resourceId))
  private val resourceFR = ResourceGen.resourceFor(resource)

  private val schemaId   = nxv + "schemaId"
  private val schema     = SchemaGen.schema(
    schemaId,
    projectRef,
    jsonContentOf("resources/schema.json") deepMerge json"""{"@id": "$schemaId"}"""
  )
  private val resourceFS = SchemaGen.resourceFor(schema)

  private val unknownResourceId  = nxv + "xxx"
  private val unknownResourceRef = Latest(unknownResourceId)

  private def content[R](resource: ResourceF[R], source: Json)(implicit enc: JsonLdEncoder[R]) =
    JsonLdContent(resource, source, None)

  private val resourceValue = content(resourceFR, resourceFR.value.source)
  private val schemaValue   = content(resourceFS, resourceFS.value.source)

  def fetch: (ResourceRef, ProjectRef) => Fetch[JsonLdContent[_, _]] =
    (ref: ResourceRef, _: ProjectRef) =>
      ref match {
        case Latest(`resourceId`)       => IO.some(resourceValue)
        case Revision(_, `schemaId`, _) => IO.some(schemaValue)
        case _                          => IO.none
      }

  def fetchProject: ProjectRef => IO[ResolverRejection, ProjectContext] =
    FetchContextDummy(Map(projectRef -> ProjectContext.unsafe(ApiMappings.empty, nxv.base, nxv.base)))
      .mapRejection(ProjectContextRejection)
      .onRead

  private val resolverId = nxv + "in-project"

  private val resourceResolution = ResolverResolutionGen.singleInProject(projectRef, fetch)

  private val multiResolution = new MultiResolution(fetchProject, resourceResolution)

  "A multi-resolution" should {

    "resolve the id as a resource" in {
      multiResolution(resourceId, projectRef).accepted shouldEqual
        MultiResolutionResult(ResourceResolutionReport(ResolverReport.success(resolverId, projectRef)), resourceValue)
    }

    "resolve the id as a resource with a specific resolver" in {
      multiResolution(resourceId, projectRef, resolverId).accepted shouldEqual
        MultiResolutionResult(ResolverReport.success(resolverId, projectRef), resourceValue)
    }

    "resolve the id as a schema" in {
      multiResolution(IdSegmentRef(schemaId, 5), projectRef).accepted shouldEqual
        MultiResolutionResult(ResourceResolutionReport(ResolverReport.success(resolverId, projectRef)), schemaValue)
    }

    "resolve the id as a schema with a specific resolver" in {
      multiResolution(IdSegmentRef(schemaId, 5), projectRef, resolverId).accepted shouldEqual
        MultiResolutionResult(ResolverReport.success(resolverId, projectRef), schemaValue)
    }

    "fail when it can't be resolved neither as a resource or a schema" in {
      multiResolution(unknownResourceId, projectRef).rejected shouldEqual
        InvalidResolution(
          unknownResourceRef,
          projectRef,
          ResourceResolutionReport(
            ResolverReport.failed(resolverId, projectRef -> ResourceNotFound(unknownResourceId, projectRef))
          )
        )
    }

    "fail with a specific resolver when it can't be resolved neither as a resource or a schema" in {
      multiResolution(unknownResourceId, projectRef, resolverId).rejected shouldEqual
        InvalidResolverResolution(
          unknownResourceRef,
          resolverId,
          projectRef,
          ResolverReport.failed(resolverId, projectRef -> ResourceNotFound(unknownResourceId, projectRef))
        )
    }

    "fail with an invalid resolver id" in {
      val invalid = "qa$%"
      multiResolution(resourceId, projectRef, invalid).rejected shouldEqual InvalidResolverId(invalid)
    }
  }

}
