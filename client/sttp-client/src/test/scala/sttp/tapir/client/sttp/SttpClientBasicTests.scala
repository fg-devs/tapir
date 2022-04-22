package sttp.tapir.client.sttp

import sttp.tapir.client.tests.ClientBasicTests

class SttpClientBasicTests extends SttpClientTests[Any] with ClientBasicTests {
  override def wsToPipe: WebSocketToPipe[Any] = implicitly

  import sttp.tapir.tests.Basic._
  testClient(showcase, (), (), Right("http://dummy"))

  tests()
}
