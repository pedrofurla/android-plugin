import sbt._

import Keys._
import AndroidKeys._
import AndroidHelpers._

object AndroidShortcuts {

  lazy val settings: Seq[Setting[_]] = Seq (
    onLoad in Global <<= (onLoad in Global) ?? identity[State],
    onLoad in Global ~= { oldState =>
      makePdAlias("rlv", "android:start-emulator") compose
      makePdAlias("rld", "android:start-device") compose
      makeAlias("pd", "android:package-debug") compose
      makeAlias("pr", "android:package-release") compose
      oldState
    }
  )

  def makePdAlias(name: String, lastTask: String) = (state: State) => {
    BuiltinCommands.addAlias(
      state, name,
      ";android:clean-apk;android:package-debug;" + lastTask
    )
  }

  def makeAlias(shortName: String, fullName: String) = (state: State) => {
    BuiltinCommands.addAlias(state, shortName, fullName)
  }
}
