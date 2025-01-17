package ch.epfl.bluebrain.nexus.delta.sdk.resolvers

import akka.http.scaladsl.model.Uri
import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ResolverGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution.FetchResource
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolutionSpec.ResourceExample
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.IdentityResolution.{ProvidedIdentities, UseCurrentCaller}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.Resolver.CrossProjectResolver
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverRejection.ResolverNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverResolutionRejection.{ProjectAccessDenied, ResourceNotFound, ResourceTypesDenied, WrappedResolverRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverValue.CrossProjectValue
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResourceResolutionReport.ResolverReport
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{IdentityResolution, Priority, Resolver, ResolverRejection, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.IOValues
import io.circe.Json
import monix.bio.{IO, UIO}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import java.time.Instant

class ResolverResolutionSpec extends AnyWordSpecLike with Matchers with IOValues with OptionValues with Inspectors {

  private val alice = User("alice", Label.unsafe("wonderland"))
  private val bob   = User("bob", Label.unsafe("wonderland"))

  implicit val aliceCaller: Caller = Caller(alice, Set(alice))

  private val project1 = ProjectRef.unsafe("org", "project1")
  private val project2 = ProjectRef.unsafe("org", "project2")
  private val project3 = ProjectRef.unsafe("org", "project3")

  val checkAcls: (ProjectRef, Set[Identity]) => UIO[Boolean] =
    (p: ProjectRef, identities: Set[Identity]) =>
      p match {
        case `project1` if identities == Set(alice) || identities == Set(bob) => UIO.pure(true)
        case `project2` if identities == Set(bob)                             => UIO.pure(true)
        case `project3` if identities == Set(alice)                           => UIO.pure(true)
        case _                                                                => UIO.pure(false)
      }

  private val resource = ResourceF(
    id = nxv + "example1",
    uris = ResourceUris(Uri("/example1")),
    rev = 5,
    types = Set(nxv + "ResourceExample", nxv + "ResourceExample2"),
    deprecated = false,
    createdAt = Instant.now(),
    createdBy = alice,
    updatedAt = Instant.now(),
    updatedBy = alice,
    schema = Latest(schemas + "ResourceExample"),
    value = ResourceExample("myResource")
  )

  private val inProjectResolver = ResolverGen.inProject(nxv + "in-project-proj-1", project1)

  def crossProjectResolver(
      id: String,
      priority: Int,
      resourceTypes: Set[Iri] = Set.empty,
      projects: NonEmptyList[ProjectRef] = NonEmptyList.of(project1, project2, project3),
      identityResolution: IdentityResolution = UseCurrentCaller
  ): CrossProjectResolver =
    CrossProjectResolver(
      nxv + id,
      project1,
      CrossProjectValue(
        Priority.unsafe(priority),
        resourceTypes,
        projects,
        identityResolution
      ),
      Json.obj(),
      Tags.empty
    )

  def listResolvers(resolvers: List[Resolver]): ProjectRef => UIO[List[Resolver]] = (_: ProjectRef) =>
    IO.pure(resolvers)
  private val emptyResolverListQuery                                              = listResolvers(List.empty[Resolver])

  val noResolverFetch: (Iri, ProjectRef) => IO[ResolverNotFound, Nothing]                     =
    (_: Iri, projectRef: ProjectRef) => IO.raiseError(ResolverNotFound(nxv + "not-found", projectRef))
  def fetchResolver(resolver: Resolver): (Iri, ProjectRef) => IO[ResolverRejection, Resolver] =
    (id: Iri, projectRef: ProjectRef) =>
      if (id == resolver.id) IO.pure(resolver)
      else IO.raiseError(ResolverNotFound(id, projectRef))

  def fetchResource(
      projectRef: ProjectRef
  ): (ResourceRef, ProjectRef) => FetchResource[ResourceExample] =
    (_: ResourceRef, p: ProjectRef) =>
      p match {
        case `projectRef` => UIO.some(resource)
        case _            => UIO.none
      }

  "The Resource resolution" when {

    def singleResolverResolution(resourceProject: ProjectRef, resolver: Resolver) =
      ResourceResolution(
        checkAcls,
        emptyResolverListQuery,
        fetchResolver(resolver),
        fetchResource(resourceProject)
      )

    def multipleResolverResolution(resourceProject: ProjectRef, resolvers: Resolver*) =
      sdk.resolvers.ResourceResolution(
        checkAcls,
        listResolvers(resolvers.toList),
        noResolverFetch,
        fetchResource(resourceProject)
      )

    "resolving with an in-project resolver" should {
      val resourceResolution = singleResolverResolution(project1, inProjectResolver)

      "fail if the resolver can't be found" in {
        val unknown = nxv + "xxx"
        resourceResolution
          .resolve(Latest(resource.id), project1, unknown)
          .rejected shouldEqual ResolverReport.failed(
          unknown,
          project1 -> WrappedResolverRejection(ResolverNotFound(unknown, project1))
        )
      }

      "fail if the resource can't be found in the project" in {
        val (report, result) = resourceResolution
          .resolveReport(
            Latest(resource.id),
            project2,
            inProjectResolver.id
          )
          .accepted

        report shouldEqual ResolverReport.failed(
          inProjectResolver.id,
          project2 -> ResourceNotFound(resource.id, project2)
        )
        result shouldEqual None
      }

      "be successful if the resource can be fetched" in {
        val (report, result) =
          resourceResolution.resolveReport(Latest(resource.id), project1, inProjectResolver.id).accepted

        report shouldEqual ResolverReport.success(inProjectResolver.id, project1)
        result.value shouldEqual resource
      }
    }

    "resolving with a cross-project resolver with using current caller resolution" should {
      "succeed at 3rd project" in {
        forAll(
          List(
            crossProjectResolver("use-current", 40, identityResolution = UseCurrentCaller),
            crossProjectResolver(
              "use-current",
              40,
              resourceTypes = resource.types + nxv.Schema,
              identityResolution = UseCurrentCaller
            )
          )
        ) { resolver =>
          val (report, result) = singleResolverResolution(project3, resolver)
            .resolveReport(Latest(resource.id), project1, resolver.id)
            .accepted

          report shouldEqual ResolverReport.success(
            resolver.id,
            project3,
            project1 -> ResourceNotFound(resource.id, project1),
            project2 -> ProjectAccessDenied(project2, UseCurrentCaller)
          )
          result.value shouldEqual resource
        }
      }

      "fail if the caller has no access to the resource project" in {
        val resolver         = crossProjectResolver(
          "use-current",
          40,
          identityResolution = UseCurrentCaller
        )
        val (report, result) = singleResolverResolution(project2, resolver)
          .resolveReport(Latest(resource.id), project1, resolver.id)
          .accepted

        report shouldEqual ResolverReport.failed(
          resolver.id,
          project1 -> ResourceNotFound(resource.id, project1),
          project2 -> ProjectAccessDenied(project2, UseCurrentCaller),
          project3 -> ResourceNotFound(resource.id, project3)
        )
        result shouldEqual None
      }

      "fail if the resource type is not defined in the cross project resolver" in {
        val resolver = crossProjectResolver(
          "use-current",
          40,
          resourceTypes = Set(nxv.Schema),
          identityResolution = UseCurrentCaller
        )

        val resourceResolution = singleResolverResolution(project3, resolver)

        val (report, result) = resourceResolution
          .resolveReport(Latest(resource.id), project1, resolver.id)
          .accepted

        report shouldEqual ResolverReport.failed(
          resolver.id,
          project1 -> ResourceNotFound(resource.id, project1),
          project2 -> ProjectAccessDenied(project2, UseCurrentCaller),
          project3 -> ResourceTypesDenied(project3, resource.types)
        )
        result shouldEqual None
      }

    }

    "resolving with a cross-project resolver with using provided entities resolution" should {
      "succeed at 2nd project" in {
        forAll(
          List(
            crossProjectResolver("provided-entities", 40, identityResolution = ProvidedIdentities(Set(bob))),
            crossProjectResolver(
              "provided-entities",
              40,
              resourceTypes = resource.types + nxv.Schema,
              identityResolution = ProvidedIdentities(Set(bob))
            )
          )
        ) { resolver =>
          val (report, result) = singleResolverResolution(project2, resolver)
            .resolveReport(Latest(resource.id), project1, resolver.id)
            .accepted

          report shouldEqual ResolverReport.success(
            resolver.id,
            project2,
            project1 -> ResourceNotFound(resource.id, project1)
          )
          result.value shouldEqual resource
        }
      }

      "fail if the provided entity has no access to the resource project" in {
        val resolver         = crossProjectResolver(
          "provided-entities",
          40,
          identityResolution = ProvidedIdentities(Set(bob))
        )
        val (report, result) = singleResolverResolution(project3, resolver)
          .resolveReport(Latest(resource.id), project1, resolver.id)
          .accepted

        report shouldEqual ResolverReport.failed(
          resolver.id,
          project1 -> ResourceNotFound(resource.id, project1),
          project2 -> ResourceNotFound(resource.id, project2),
          project3 -> ProjectAccessDenied(project3, ProvidedIdentities(Set(bob)))
        )
        result shouldEqual None
      }
    }

    "resolving with multiple resolvers" should {

      "be successful with the in-project resolver after failing a first time" in {
        val resolution = multipleResolverResolution(
          project1,
          crossProjectResolver("cross-project-1", priority = 10, resourceTypes = Set(nxv.Schema)),
          crossProjectResolver("cross-project-2", priority = 40),
          inProjectResolver
        )

        val (report, result) = resolution.resolveReport(Latest(resource.id), project1).accepted

        report shouldEqual ResourceResolutionReport(
          ResolverReport.failed(
            nxv + "cross-project-1",
            project1 -> ResourceTypesDenied(project1, resource.types),
            project2 -> ProjectAccessDenied(project2, UseCurrentCaller),
            project3 -> ResourceNotFound(resource.id, project3)
          ),
          ResolverReport.success(inProjectResolver.id, project1)
        )

        result.value shouldEqual resource
      }

      "be successful with the last resolver" in {
        val resolution = multipleResolverResolution(
          project3,
          crossProjectResolver("cross-project-1", priority = 10, resourceTypes = Set(nxv.Schema)),
          crossProjectResolver("cross-project-2", priority = 40, projects = NonEmptyList.of(project3)),
          inProjectResolver
        )

        val (report, result) = resolution.resolveReport(Latest(resource.id), project1).accepted

        report shouldEqual ResourceResolutionReport(
          ResolverReport.failed(
            nxv + "cross-project-1",
            project1 -> ResourceNotFound(resource.id, project1),
            project2 -> ProjectAccessDenied(project2, UseCurrentCaller),
            project3 -> ResourceTypesDenied(project3, resource.types)
          ),
          ResolverReport.failed(
            inProjectResolver.id,
            project1 -> ResourceNotFound(resource.id, project1)
          ),
          ResolverReport.success(nxv + "cross-project-2", project3)
        )

        result.value shouldEqual resource
      }

      "fail if no resolver matches" in {
        val resolution = multipleResolverResolution(
          project2,
          crossProjectResolver("cross-project-1", priority = 10, resourceTypes = Set(nxv.Schema)),
          crossProjectResolver("cross-project-2", priority = 40, projects = NonEmptyList.of(project3)),
          inProjectResolver
        )

        val (report, result) = resolution.resolveReport(Latest(resource.id), project1).accepted

        report shouldEqual ResourceResolutionReport(
          ResolverReport.failed(
            nxv + "cross-project-1",
            project1 -> ResourceNotFound(resource.id, project1),
            project2 -> ProjectAccessDenied(project2, UseCurrentCaller),
            project3 -> ResourceNotFound(resource.id, project3)
          ),
          ResolverReport.failed(
            inProjectResolver.id,
            project1 -> ResourceNotFound(resource.id, project1)
          ),
          ResolverReport.failed(
            nxv + "cross-project-2",
            project3 -> ResourceNotFound(resource.id, project3)
          )
        )
        result shouldEqual None
      }

    }

  }

}

object ResolverResolutionSpec {

  final case class ResourceExample(value: String)

}
