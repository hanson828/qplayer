import QtQuick
import miuix.Core

// Plugin update prompt, kept in its own document on purpose: qml4j compiles a
// whole QML file's construction into one generated constructor, and Main.qml is
// close enough to the JVM's 64 KB method limit that another inline Dialog there
// fails the build with MethodTooLargeException. A separate file gets its own
// generated class.
//
// This owns the whole "only one update dialog at a time" rule. The app's own
// update always wins: a QPlayer release can change what a plugin requires
// (minHostVersion), so updating the app first is the order that cannot deadlock.
// The host holds the prompt back while an app update is pending; the watchers
// here cover the opposite ordering, where the catalog refresh lands first and
// this dialog is already up when the app's check returns.
Item {
    id: control

    readonly property bool opened: dialogLoader.item ? dialogLoader.item.opened : false
    property bool _loaded: player.pluginUpdateAvailable && !player.updateAvailable

    function close() { if (dialogLoader.item) dialogLoader.item.close() }

    property bool appUpdateWatch: player.updateAvailable
    onAppUpdateWatchChanged: {
        if (!player.updateAvailable || !control.opened) return
        // Step aside without consuming the offer -- the host proposes this same
        // plugin again once the app dialog closes.
        player.deferPluginUpdate()
        dialogLoader.item.close()
    }
    property int appUpdateRevWatch: player.updatePromptRevision
    onAppUpdateRevWatchChanged: {
        if (!player.updateAvailable || !control.opened) return
        player.deferPluginUpdate()
        dialogLoader.item.close()
    }

    property bool pluginUpdateWatch: player.pluginUpdateAvailable
    onPluginUpdateWatchChanged: {
        if (!player.pluginUpdateAvailable || control.opened) return
        if (player.updateAvailable) return
        control._loaded = true
        if (dialogLoader.item) dialogLoader.item.open()
    }

    Loader {
        id: dialogLoader
        anchors.fill: parent
        active: control._loaded
        onLoaded: if (item && player.pluginUpdateAvailable && !player.updateAvailable) item.open()
        sourceComponent: Component {
            Dialog {

                topInset: settings.topInset

                bottomInset: settings.bottomInset
                id: dialog
                title: i18n.t("update.plugin.title")
                icon: "extension"
                text: i18n.t("update.plugin.body", player.pluginUpdateName,
                             player.pluginUpdateVersion)
                      .replace("{2}", player.pluginUpdateInstalledVersion)
                acceptText: i18n.t("update.now")
                rejectText: i18n.t("update.later")
                // Routes into the normal catalog install path, so the permission sheet
                // still appears before the new version is activated.
                onAccepted: player.installCatalogPlugin(player.pluginUpdateId)
                onClosed: player.acknowledgePluginUpdate()
            }
        }
    }
}
