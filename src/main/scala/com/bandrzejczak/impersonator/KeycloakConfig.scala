package com.bandrzejczak.impersonator

case class KeycloakConfig(
                           authServerUrl: String,
                           realm: String,
                           adminUsername: String,
                           adminPassword: String,
                           clientId: String,
                           redirectUri: String
                         )
