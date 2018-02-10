package com.bandrzejczak.impersonator

case class TokenResponse(
                          access_token: String,
                          expires_in: Int,
                          refresh_token: String,
                          refresh_expires_in: Int
                        )
