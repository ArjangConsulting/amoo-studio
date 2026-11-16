package dev.amoo.studio

/** A menu shortcut: the platform command key (Cmd on macOS, Ctrl elsewhere) plus [key], which is an uppercase letter or a digit. */
data class MenuShortcut(val key: Char, val shift: Boolean = false)

data class MenuCommand(val title: String, val event: StudioEvent, val shortcut: MenuShortcut? = null)

data class MenuGroup(val title: String, val commands: List<MenuCommand>)

/** The application menu bar. Every command emits an existing [StudioEvent]; the desktop host only renders it. */
fun studioMenus(): List<MenuGroup> = listOf(
	MenuGroup("File", listOf(
		MenuCommand("New Test", StudioEvent.NewTest, MenuShortcut('N')),
		MenuCommand("Open Test…", StudioEvent.OpenTest, MenuShortcut('O')),
		MenuCommand("Save", StudioEvent.SaveTest, MenuShortcut('S')),
		MenuCommand("Save As…", StudioEvent.SaveTestAs, MenuShortcut('S', shift = true)),
	)),
	MenuGroup("View", StudioSection.entries.mapIndexed { index, section ->
		MenuCommand(section.title, StudioEvent.SelectSection(section), MenuShortcut('1' + index))
	}),
	MenuGroup("Help", listOf(
		MenuCommand("About Amoo Studio", StudioEvent.ShowAbout),
		MenuCommand("Reveal Logs", StudioEvent.RevealLogs),
	)),
)
