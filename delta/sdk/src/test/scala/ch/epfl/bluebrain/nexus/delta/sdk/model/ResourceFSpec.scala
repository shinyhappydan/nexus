package ch.epfl.bluebrain.nexus.delta.sdk.model

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.acls
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{PermissionsGen, ResourceGen}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.Fixtures
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestHelpers, TestMatchers}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ResourceFSpec
    extends AnyWordSpecLike
    with Matchers
    with CirceLiteral
    with TestHelpers
    with IOValues
    with TestMatchers
    with Fixtures {

  implicit val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  "A ResourceF of a permission" should {
    val updatedBy = User("maria", Label.unsafe("bbp"))
    val resource  = PermissionsGen.resourceFor(Set(acls.read, acls.write), rev = 1, updatedBy = updatedBy)

    "be converted to Json-LD compacted" in {
      resource.toCompactedJsonLd.accepted.json shouldEqual jsonContentOf("resource-compacted.jsonld")
    }

    "be converted to Json-LD expanded" in {
      resource.toExpandedJsonLd.accepted.json shouldEqual jsonContentOf("resource-expanded.jsonld")
    }

    "be converted to Dot format" in {
      resource.toDot.accepted.toString should equalLinesUnordered(contentOf("resource-dot.dot"))

    }

    "be converted to NTriples format" in {
      resource.toNTriples.accepted.toString should equalLinesUnordered(contentOf("resource-ntriples.nt"))
    }
  }

  "A ResourceF of a data resource" should {

    val resourceF = ResourceGen.resourceFor(
      ResourceGen.resource(
        nxv + "testId",
        ProjectRef.unsafe("org", "proj"),
        jsonContentOf("resources/resource-with-context.json")
      ),
      Set(nxv + "TestResource"),
      am = ApiMappings("_" -> schemas.resources)
    )

    "be converted to Json-LD compacted" in {
      resourceF.toCompactedJsonLd.accepted.json shouldEqual jsonContentOf(
        "resources/resource-with-context-and-metadata.json"
      )
    }
  }

}
