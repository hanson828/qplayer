import QtQuick
import miuix.Core
import design

Item {
    id: control
    anchors.fill: parent
    property bool active: false
    property string passwordText: ""
    signal closed()

    onActiveChanged: {
        if (active) {
            control.passwordText = ""
            dialog.open()
        } else if (dialog.opened) {
            dialog.close()
        }
    }

    function submit() {
        if (control.passwordText.length === 0) return
        settings.setTransferPassphrase(control.passwordText)
        settings.invoke("confirmImportSettingsPassword")
    }

    Dialog {
        id: dialog
        objectName: "importConfigPasswordDialog"
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        maxWidth: 440
        icon: "lock"
        title: i18n.t("settings.importPassword.title")
        text: i18n.t("settings.importPassword.desc")
        showAcceptButton: false
        rejectText: i18n.t("common.cancel")
        onRejected: settings.invoke("cancelImportSettingsPassword")
        onClosed: {
            control.passwordText = ""
            control.closed()
        }

        Item {
            width: dialog.contentWidth
            height: confirmBtn.y + confirmBtn.height

            TextField {
                id: importPasswordField
                objectName: "importConfigPasswordField"
                overlayField: true
                width: parent.width
                type: "outlined"
                label: i18n.t("settings.importPassword.label")
                isPassword: true
                text: control.passwordText
                errorText: settings.importPasswordError
                onTextChanged: control.passwordText = text
                onAccepted: control.submit()
            }

            Button {
                id: confirmBtn
                objectName: "importConfigPasswordConfirmButton"
                y: importPasswordField.height + Ui.space3
                width: parent.width
                height: 44
                type: "filled"
                text: i18n.t("settings.importPassword.confirm")
                enabled: control.passwordText.length > 0
                onClicked: control.submit()
            }
        }
    }
}
