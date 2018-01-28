package com.bandrzejczak.impersonator

import java.time.ZonedDateTime

import com.softwaremill.sttp._
import com.softwaremill.sttp.circe._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class Impersonator(config: KeycloakConfig)(implicit sttpBackend: SttpBackend[Future, Nothing], ec: ExecutionContext) {

  def impersonate(username: String): Future[(String, Duration)] = {
    for {
      impersonatorToken <- obtainImpersonatorToken()
      userId <- getUserId(username, impersonatorToken)
      impersonatedUserCookies <- impersonateUser(impersonatorToken, userId)
      userTokenAndTtl <- obtainTokenBasedOnIdentity(impersonatedUserCookies)
    } yield userTokenAndTtl
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

  private def impersonateUser(token: String, userId: String): Future[Seq[(String, String)]] = {
    sttp
      .post(uri"${config.authServerUrl}/admin/realms/${config.realm}/users/$userId/impersonation")
      .auth.bearer(token)
      .send()
      .map { r =>
        val currentTime = ZonedDateTime.now()
        r.cookies
          .filter(_.expires.forall(_.isAfter(currentTime)))
          .map(cookie => (cookie.name, cookie.value))
      }
  }

  private def obtainTokenBasedOnIdentity(impersonatedUserCookies: Seq[(String, String)]): Future[(String, Duration)] = {
    val authUri = uri"${config.authServerUrl}/realms/${config.realm}/protocol/openid-connect/auth"
    sttp
      .get(uri"$authUri?response_mode=fragment&response_type=token&client_id=${config.clientId}&redirect_uri=${config.redirectUri}")
      .cookies(impersonatedUserCookies: _*)
      .followRedirects(false) // The response comes in the form of redirect
      .send()
      .map(extractTokenAndExpiration)
      .flatMap {
        case Some(tokenAndDuration) => Future.successful(tokenAndDuration)
        case None => Future.failed(new RuntimeException("Failed to extract token and duration from response headers"))
      }
  }

  private def extractTokenAndExpiration(response: Response[String]): Option[(String, Duration)] = {
    for {
      location <- response.headers.find(_._1 == "Location").map(_._2)
      queryParams <- extractQueryParams(location)
      token <- queryParams.get("access_token")
      expiration <- queryParams.get("expires_in").map(parseExpirationTime)
    } yield (token, expiration)
  }

  private def extractQueryParams(location: String): Option[Map[String, String]] = {
    val uri: Uri = uri"$location"
    uri.fragment.map { fragment =>
      fragment
        .split("&")
        .toSeq
        .map(_.split("=").toSeq)
        .map { case Seq(x, y) => (x, y) }
        .toMap
    }
  }

  private def parseExpirationTime(timeInSeconds: String): Duration = {
    timeInSeconds.toLong.seconds
  }
}
