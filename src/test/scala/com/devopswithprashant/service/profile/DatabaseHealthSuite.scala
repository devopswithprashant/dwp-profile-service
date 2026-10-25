package com.devopswithprashant.service.profile

import cats.effect.IO
import munit.CatsEffectSuite

class DatabaseHealthSuite extends CatsEffectSuite:

  test("reports a successful database probe as healthy"):
    assertIO(DatabaseHealth.check(IO.unit), true)

  test("reports and logs a failed database probe as unhealthy"):
    assertIO(
      DatabaseHealth.check(
        IO.raiseError[Unit](new IllegalStateException("private connection detail"))
      ),
      false
    )
