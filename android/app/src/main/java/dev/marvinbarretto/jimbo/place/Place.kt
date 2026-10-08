package dev.marvinbarretto.jimbo.place

/** What the shell is told instead of coordinates. */
enum class Place(val wire: String) {
    HOME("home"),
    GYM("gym"),
    OTHER("other"),
}
