package com.devopswithprashant.service.profile

import cats.effect.{IO, IOApp}

object Main extends IOApp.Simple:
  val run = DwpProfileServiceServer.run[IO]
