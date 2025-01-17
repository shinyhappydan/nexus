package ch.epfl.bluebrain.nexus.delta.sdk.schemas

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.Resolve
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceGen}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResourceResolutionReport
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.Resource
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaRejection.InvalidSchemaResolution
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.Fixtures
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestHelpers}
import monix.bio.IO
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.collection.immutable.VectorMap

class SchemaImportsSpec
    extends AnyWordSpecLike
    with Matchers
    with TestHelpers
    with IOValues
    with OptionValues
    with CirceLiteral
    with Fixtures {

  private val alice                = User("alice", Label.unsafe("wonderland"))
  implicit val aliceCaller: Caller = Caller(alice, Set(alice))

  "A SchemaImports" should {
    val neuroshapes       = "https://neuroshapes.org"
    val parcellationlabel = iri"$neuroshapes/dash/parcellationlabel"
    val json              = jsonContentOf("schemas/parcellationlabel.json")
    val projectRef        = ProjectGen.project("org", "proj").ref

    val entitySource = jsonContentOf("schemas/entity.json")

    val entityExpandedSchema        = ExpandedJsonLd(jsonContentOf("schemas/entity-expanded.json")).accepted
    val identifierExpandedSchema    = ExpandedJsonLd(jsonContentOf("schemas/identifier-expanded.json")).accepted
    val licenseExpandedSchema       = ExpandedJsonLd(jsonContentOf("schemas/license-expanded.json")).accepted
    val propertyValueExpandedSchema = ExpandedJsonLd(jsonContentOf("schemas/property-value-expanded.json")).accepted

    val expandedSchemaMap = Map(
      iri"$neuroshapes/commons/entity" ->
        Schema(
          iri"$neuroshapes/commons/entity",
          projectRef,
          Tags.empty,
          entitySource,
          entityExpandedSchema.toCompacted(entitySource.topContextValueOrEmpty).accepted,
          NonEmptyList.of(
            entityExpandedSchema,
            identifierExpandedSchema,
            licenseExpandedSchema,
            propertyValueExpandedSchema
          )
        )
    )

    // format: off
    val resourceMap                          = VectorMap(
      iri"$neuroshapes/commons/vocabulary" -> jsonContentOf("schemas/vocabulary.json"),
      iri"$neuroshapes/wrong/vocabulary"   -> jsonContentOf("schemas/vocabulary.json").replace("owl:Ontology", "owl:Other")
    ).map { case (iri, json) => iri -> ResourceGen.resource(iri, projectRef, json) }
    // format: on

    val errorReport = ResourceResolutionReport()

    val fetchSchema: Resolve[Schema]     = {
      case (ref, `projectRef`, _) => IO.fromOption(expandedSchemaMap.get(ref.iri), errorReport)
      case (_, _, _)              => IO.raiseError(errorReport)
    }
    val fetchResource: Resolve[Resource] = {
      case (ref, `projectRef`, _) => IO.fromOption(resourceMap.get(ref.iri), errorReport)
      case (_, _, _)              => IO.raiseError(errorReport)
    }

    val imports = new SchemaImports(fetchSchema, fetchResource)

    "resolve all the imports" in {
      val expanded = ExpandedJsonLd(json).accepted
      val result   = imports.resolve(parcellationlabel, projectRef, expanded).accepted

      result.toList.toSet shouldEqual
        (resourceMap.take(1).values.map(_.expanded).toSet ++ Set(
          entityExpandedSchema,
          identifierExpandedSchema,
          licenseExpandedSchema,
          propertyValueExpandedSchema
        ) + expanded)
    }

    "fail to resolve an import if it is not found" in {
      val other        = iri"$neuroshapes/other"
      val other2       = iri"$neuroshapes/other2"
      val parcellation = json deepMerge json"""{"imports": ["$neuroshapes/commons/entity", "$other", "$other2"]}"""
      val expanded     = ExpandedJsonLd(parcellation).accepted

      imports.resolve(parcellationlabel, projectRef, expanded).rejected shouldEqual
        InvalidSchemaResolution(
          parcellationlabel,
          schemaImports = Map(ResourceRef(other) -> errorReport, ResourceRef(other2) -> errorReport),
          resourceImports = Map(ResourceRef(other) -> errorReport, ResourceRef(other2) -> errorReport),
          nonOntologyResources = Set.empty
        )
    }

    "fail to resolve an import if it is a resource without owl:Ontology type" in {
      val wrong        = iri"$neuroshapes/wrong/vocabulary"
      val parcellation = json deepMerge json"""{"imports": ["$neuroshapes/commons/entity", "$wrong"]}"""
      val expanded     = ExpandedJsonLd(parcellation).accepted

      imports.resolve(parcellationlabel, projectRef, expanded).rejected shouldEqual
        InvalidSchemaResolution(
          parcellationlabel,
          schemaImports = Map(ResourceRef(wrong) -> errorReport),
          resourceImports = Map.empty,
          nonOntologyResources = Set(ResourceRef(wrong))
        )
    }
  }
}
