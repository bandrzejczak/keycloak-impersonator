package com.bandrzejczak.impersonator


import com.softwaremill.sttp._
import com.softwaremill.sttp.circe._

import scala.concurrent.{ExecutionContext, Future}

class Impersonator(config: KeycloakConfig)
                  (implicit sttpBackend: SttpBackend[Future, Nothing], ec: ExecutionContext) {

  def impersonate(username: String): Future[TokenResponse] = {
    for {
      impersonatorToken <- obtainImpersonatorToken()
      userId <- getUserId(username, impersonatorToken)
      impersonatedUserToken <- exchangeToken(impersonatorToken, userId)
    } yield impersonatedUserToken
  }

  private def obtainImpersonatorToken(): Future[String] = {
    import io.circe.generic.auto._
    sttp
      .post(uri"${config.authServerUrl}/realms/${config.realm}/protocol/openid-connect/token")
      .body(
        "grant_type" -> "password",
        "username" -> config.impersonatorUsername,
        "password" -> config.impersonatorPassword,
        "client_id" -> config.clientId
      )
      .response(asJson[TokenResponse])
      .send()
      .flatMap {
        _.body match {
          case Left(error) => Future.failed(new RuntimeException(error))
          case Right(Left(circeError)) => Future.failed(circeError)
          case Right(Right(tokenResponse)) => Future.successful(tokenResponse.access_token)
        }
      }
  }

  private def getUserId(username: String, token: String): Future[String] = {
    import io.circe.generic.auto._
    sttp
      .get(uri"${config.authServerUrl}/admin/realms/${config.realm}/users?username=$username")
      .auth.bearer(token)
      .response(asJson[List[UserResponse]])
      .send()
      .flatMap {
        _.body match {
          case Left(error) => Future.failed(new RuntimeException(error))
          case Right(Left(circeError)) => Future.failed(circeError)
          case Right(Right(user :: Nil)) => Future.successful(user.id)
          case _ => Future.failed(new RuntimeException(s"Couldn't find a single user with username $username"))
        }
      }
  }

  private def exchangeToken(token: String, userId: String): Future[TokenResponse] = {
    import io.circe.generic.auto._
    sttp
      .post(uri"${config.authServerUrl}/realms/${config.realm}/protocol/openid-connect/token")
      .body(
        "grant_type" -> "urn:ietf:params:oauth:grant-type:token-exchange",
        "client_id" -> config.clientId,
        "requested_subject" -> userId,
        "subject_token" -> token
      )
      .response(asJson[TokenResponse])
      .send()
      .flatMap {
        _.body match {
          case Left(error) => Future.failed(new RuntimeException(error))
          case Right(Left(circeError)) => Future.failed(circeError)
          case Right(Right(tokenResponse)) => Future.successful(tokenResponse)
        }
      }
  }

}
