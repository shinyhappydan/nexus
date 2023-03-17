package ch.epfl.bluebrain.nexus.delta.sdk.resources

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.{ShaclEngine, ValidationReport}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution.ResourceResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.{InvalidJsonLdFormat, InvalidResource, InvalidSchemaRejection, ReservedResourceId, ResourceShaclEngineRejection, SchemaIsDeprecated}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.Schema
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import monix.bio.IO

final class ResourceValidator(resourceResolution: ResourceResolution[Schema])(implicit
    jsonLdApi: JsonLdApi
) {
  private def toGraph(id: Iri, expanded: ExpandedJsonLd): IO[ResourceRejection, Graph] =
    IO.fromEither(expanded.toGraph).mapError(err => InvalidJsonLdFormat(Some(id), err))

  def validationReport(
      projectRef: ProjectRef,
      schemaRef: ResourceRef,
      caller: Caller,
      resourceId: Iri,
      expanded: ExpandedJsonLd
  ): IO[ResourceRejection, (Option[ValidationReport], ResourceRef.Revision, ProjectRef)] =
    if (isUnconstrained(schemaRef))
      assertNotReservedId(resourceId) >>
        toGraph(resourceId, expanded) >>
        IO.pure((None, ResourceRef.Revision(schemas.resources, 1), projectRef))
    else
      for {
        _      <- assertNotReservedId(resourceId)
        graph  <- toGraph(resourceId, expanded)
        schema <- resolveSchema(resourceResolution, projectRef, schemaRef, caller)
        report <- shaclValidate(schemaRef, resourceId, schema, graph)
        _      <- IO.raiseWhen(!report.isValid())(InvalidResource(resourceId, schemaRef, report, expanded))
      } yield (Some(report), ResourceRef.Revision(schema.id, schema.rev), schema.value.project)

  private def shaclValidate(schemaRef: ResourceRef, resourceId: Iri, schema: ResourceF[Schema], graph: Graph)(implicit
      jsonLdApi: JsonLdApi
  ) = {
    ShaclEngine(graph ++ schema.value.ontologies, schema.value.shapes, reportDetails = true, validateShapes = false)
      .mapError(ResourceShaclEngineRejection(resourceId, schemaRef, _))
  }

  private def assertNotDeprecated(schema: ResourceF[Schema]) = {
    IO.raiseWhen(schema.deprecated)(SchemaIsDeprecated(schema.value.id))
  }

  private def assertNotReservedId(resourceId: Iri) = {
    IO.raiseWhen(resourceId.startsWith(contexts.base))(ReservedResourceId(resourceId))
  }

  private def isUnconstrained(schemaRef: ResourceRef) = {
    schemaRef == Latest(schemas.resources) || schemaRef == ResourceRef.Revision(schemas.resources, 1)
  }

  private def resolveSchema(
      resourceResolution: ResourceResolution[Schema],
      projectRef: ProjectRef,
      schemaRef: ResourceRef,
      caller: Caller
  ) = {
    resourceResolution
      .resolve(schemaRef, projectRef)(caller)
      .mapError(InvalidSchemaRejection(schemaRef, projectRef, _))
      .tapEval(schema => assertNotDeprecated(schema))
  }
}
