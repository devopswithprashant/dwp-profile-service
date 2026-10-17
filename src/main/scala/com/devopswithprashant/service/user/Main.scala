package com.devopswithprashant.service.user

import cats.effect.{IO, IOApp}

object Main extends IOApp.Simple:
  val run = DwpuserserviceServer.run[IO]
