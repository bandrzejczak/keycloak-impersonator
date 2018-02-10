package com.bandrzejczak.impersonator

import com.softwaremill.sttp.SttpBackend
import com.softwaremill.sttp.akkahttp.AkkaHttpBackend

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object Main {
  def main(args: Array[String]): Unit = {
    require(args.length >= 1)

    implicit val sttpBackend: SttpBackend[Future, Nothing] = AkkaHttpBackend()

    val config = KeycloakConfig(
      "http://localhost:8080/auth",
      "master",
      "admin",
      "admin",
      "test",
      "http://bandrzejczak.com"
    )
    val impersonator = new Impersonator(config)

    val username = args.head
    val futureToken = impersonator.impersonate(username)
    val TokenResponse(token, validFor, _, _) = Await.result(futureToken, 5.seconds)
    println(s"Got new token for user $username, that is going to be valid for the next $validFor seconds:")
    println(token)

    sttpBackend.close()
  }
}
