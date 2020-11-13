package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.ResolverResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.Resolver.{CrossProjectResolver, InProjectResolver}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverType._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverValue.{CrossProjectValue, InProjectValue}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{AccessUrl, Label, ResourceF, ResourceRef}

/**
  * Enumeration of Resolver state types
  */
sealed trait ResolverState extends Product with Serializable {

  /**
    * @return the schema reference that resolvers conforms to
    */
  final def schema: ResourceRef = Latest(schemas.resolvers)

  /**
    * Converts the state into a resource representation.
    */
  def toResource(mappings: ApiMappings, base: ProjectBase): Option[ResolverResource]

}

object ResolverState {

  /**
    * Initial resolver state.
    */
  final case object Initial extends ResolverState {
    override def toResource(mappings: ApiMappings, base: ProjectBase): Option[ResolverResource] = None
  }

  /**
    * State for an existing in project resolver
    * @param id                the id of the resolver
    * @param project           the project it belongs to
    * @param value             additional fields to configure the resolver
    * @param tags              the collection of tag aliases
    * @param rev               the current state revision
    * @param deprecated        the current state deprecation status
    * @param createdAt         the instant when the resource was created
    * @param createdBy         the subject that created the resource
    * @param updatedAt         the instant when the resource was last updated
    * @param updatedBy         the subject that last updated the resource
    */
  final case class Current(
      id: Iri,
      project: ProjectRef,
      value: ResolverValue,
      tags: Map[Label, Long],
      rev: Long,
      deprecated: Boolean,
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ) extends ResolverState {

    def resolver: Resolver = {
      value match {
        case InProjectValue(priority)                                                 =>
          InProjectResolver(
            id = id,
            project = project,
            priority = priority,
            tags = tags
          )
        case CrossProjectValue(priority, resourceTypes, projects, identityResolution) =>
          CrossProjectResolver(
            id = id,
            project = project,
            resourceTypes = resourceTypes,
            projects = projects,
            identityResolution = identityResolution,
            priority = priority,
            tags = tags
          )
      }
    }

    override def toResource(mappings: ApiMappings, base: ProjectBase): Option[ResolverResource] =
      Some(
        ResourceF(
          id = AccessUrl.resolver(project, id)(_).iri,
          accessUrl = AccessUrl.resolver(project, id)(_).shortForm(mappings, base),
          rev = rev,
          types = value.tpe match {
            case InProject    => Set(nxv.Resolver, nxv.InProject)
            case CrossProject => Set(nxv.Resolver, nxv.CrossProject)
          },
          deprecated = deprecated,
          createdAt = createdAt,
          createdBy = createdBy,
          updatedAt = updatedAt,
          updatedBy = updatedBy,
          schema = schema,
          value = resolver
        )
      )
  }

}
