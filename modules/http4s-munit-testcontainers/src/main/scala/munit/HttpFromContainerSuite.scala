/*
 * Copyright 2020-2021 Alejandro Hernández <https://github.com/alejandrohdezma>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package munit

import cats.effect.IO
import cats.effect.SyncIO

import com.dimafeng.testcontainers.SingleContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import org.http4s.ContextRequest
import org.http4s.Request
import org.http4s.Response
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.client.asynchttpclient.AsyncHttpClient

/**
 * Base class for suites testing HTTP servers running in testcontainers.
 *
 * The container must expose an HTTP server in the 8080 port.
 *
 * @author Alejandro Hernández
 * @author José Gutiérrez
 */
abstract class HttpFromContainerSuite extends Http4sSuite[Unit] with CatsEffectFunFixtures with TestContainerForAll {

  final class ContainerUriExtractor(fn: PartialFunction[Containers, Uri]) extends Function1[Containers, Option[Uri]] {
    def apply(containers: Containers): Option[Uri] = fn.lift(containers)
  }

  /**
   * This list contains ways to get the container's URI. The first succesfull URI that this
   * list creates will be used as the test's base URI.
   *
   * By default it will only match `SingleContainer` by setting the URI to localhost with
   * the container's first mapped port.
   *
   * If you want to add support for other containers you can add a new value to this list
   * or override it completely:
   *
   * {{{
   * override def http4sMUnitContainerUriExtractors: List[ContainerUriExtractor] =
   *   super.http4sMUnitContainerUriExtractors ++
   *     List(new ContainerUriExtractor({ case c: MyContainer[_] => c.uri }))
   * }}}
   */
  def http4sMUnitContainerUriExtractors: List[ContainerUriExtractor] = List(
    new ContainerUriExtractor({
      case c: SingleContainer[_] if c.exposedPorts.nonEmpty =>
        Uri.unsafeFromString(s"http://localhost:${c.mappedPort(c.exposedPorts.head)}")
    })
  )

  def httpClient: SyncIO[FunFixture[Client[IO]]] = ResourceFixture(AsyncHttpClient.resource[IO]())

  implicit class TestCreatorOps(private val testCreator: TestCreator) {

    def apply(body: Response[IO] => Any)(implicit loc: munit.Location): Unit =
      testCreator.execute[Client[IO]](a => b => httpClient.test(a)(b)(loc), body) { client: Client[IO] =>
        withContainers { (containers: Containers) =>
          val uri = http4sMUnitContainerUriExtractors.view
            .map(_(containers))
            .collectFirst { case Some(uri) => uri }
            .getOrElse(fail("Unable to get container's URI"))
            .resolve(testCreator.request.req.uri)

          client.run(testCreator.request.req.withUri(uri))
        }
      }

  }

  /**
   * Declares a test for the provided request.
   *
   * @example
   * {{{
   * test(GET(uri"users" / 42)) { response =>
   *    // test body
   * }
   * }}}
   */
  def test(request: IO[Request[IO]]) = TestCreator(ContextRequest((), request.unsafeRunSync()))

}
