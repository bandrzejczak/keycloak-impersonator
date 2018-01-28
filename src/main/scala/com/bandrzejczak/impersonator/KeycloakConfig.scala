package com.bandrzejczak.impersonator

case class KeycloakConfig(
                           authServerUrl: String,
                           realm: String,
                           impersonatorUsername: String,
                           impersonatorPassword: String,
                           clientId: String,
                           redirectUri: String
                         )
