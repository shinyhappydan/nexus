package ch.epfl.bluebrain.nexus.delta.sdk.generators

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution.{FetchResource, ResourceResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverRejection.ResolverNotFound
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, ProjectRef, ResourceRef}
import monix.bio.{IO, UIO}

object ResourceResolutionGen {

  /**
    * Create a resource resolution based on a single in-project resolver
    * @param projectRef
    *   the project
    * @param fetchResource
    *   how to fetch the resource
    */
  def singleInProject[R](
      projectRef: ProjectRef,
      fetchResource: (ResourceRef, ProjectRef) => FetchResource[R]
  ): ResourceResolution[R] = {
    val resolver = ResolverGen.inProject(nxv + "in-project", projectRef)

    resolvers.ResourceResolution(
      (_: ProjectRef, _: Set[Identity]) => UIO.pure(false),
      (_: ProjectRef) => IO.pure(List(resolver)),
      (resolverId: Iri, p: ProjectRef) =>
        if (resolverId == resolver.id && p == resolver.project)
          IO.pure(resolver)
        else
          IO.raiseError(ResolverNotFound(resolverId, p)),
      fetchResource
    )

  }

}
