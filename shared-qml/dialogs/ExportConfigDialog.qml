import QtQuick
import miuix.Core
import design
import "../components"

Item {
    id: control
    anchors.fill: parent
    property bool active: false
    property bool enteringPassword: false
    property string passwordText: ""
    signal closed()
    signal exportEncrypted()
    signal exportPlain()

    onActiveChanged: {
        if (active) {
            control.enteringPassword = false
            control.passwordText = ""
            dialog.open()
        } else if (dialog.opened) {
            dialog.close()
        }
    }

    function submitEncrypted() {
        var pwd = control.passwordText
        if (!pwd || pwd.length === 0) return
        settings.setTransferPassphrase(pwd)
        control.passwordText = ""
        control.enteringPassword = false
        dialog.close()
        control.exportEncrypted()
    }

    Dialog {
        id: dialog
        objectName: "exportConfigDialog"
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        maxWidth: 480
        icon: control.enteringPassword ? "lock" : "upload_file"
        title: i18n.t("settings.exportConfig.dialogTitle")
        text: control.enteringPassword
              ? i18n.t("settings.exportEncryptedConfig.passwordHint")
              : i18n.t("settings.exportConfig.dialogDesc")
        showAcceptButton: false
        rejectText: i18n.t("common.cancel")
        onClosed: {
            control.passwordText = ""
            control.enteringPassword = false
            control.closed()
        }

        Item {
            id: optionsContainer
            width: dialog.contentWidth
            height: control.enteringPassword
                    ? (backBtn.y + backBtn.height)
                    : (cardPlain.y + cardPlain.height)

            Rectangle {
                id: cardEncrypted
                objectName: "exportEncryptedCard"
                visible: !control.enteringPassword
                width: parent.width
                height: Math.max(76, descEnc.y + descEnc.implicitHeight + 14)
                radius: Ui.radiusMd
                color: mouseEnc.pressed ? Ui.surfacePressed
                       : (mouseEnc.containsMouse ? Ui.surfaceHover : Ui.surfaceSunken)
                Behavior on color { ColorAnimation { duration: 140; easing.type: Easing.OutCubic } }

                Glyph {
                    x: 16
                    y: (parent.height - height) / 2
                    name: "lock"
                    color: Ui.accent
                }

                Text {
                    id: titleEnc
                    x: 52
                    y: 14
                    width: Math.max(0, parent.width - 68)
                    text: i18n.t("settings.exportEncryptedConfig.title")
                    color: Ui.textPrimary
                    font.pixelSize: Ui.textBody
                    font.weight: Ui.weightBold
                    wrapMode: Text.Wrap
                }

                Text {
                    id: descEnc
                    x: 52
                    y: titleEnc.y + titleEnc.implicitHeight + 4
                    width: Math.max(0, parent.width - 68)
                    text: i18n.t("settings.exportEncryptedConfig.desc")
                    color: Ui.textSecondary
                    font.pixelSize: Ui.textCaption
                    wrapMode: Text.Wrap
                }

                MouseArea {
                    id: mouseEnc
                    anchors.fill: parent
                    enabled: !control.enteringPassword
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: {
                        control.passwordText = ""
                        control.enteringPassword = true
                    }
                }
            }

            Rectangle {
                id: cardPlain
                objectName: "exportPlainCard"
                visible: !control.enteringPassword
                y: cardEncrypted.y + cardEncrypted.height + Ui.space3
                width: parent.width
                height: Math.max(76, descPlain.y + descPlain.implicitHeight + 14)
                radius: Ui.radiusMd
                color: mousePlain.pressed ? Ui.surfacePressed
                       : (mousePlain.containsMouse ? Ui.surfaceHover : Ui.surfaceSunken)
                Behavior on color { ColorAnimation { duration: 140; easing.type: Easing.OutCubic } }

                Glyph {
                    x: 16
                    y: (parent.height - height) / 2
                    name: "description"
                    color: Ui.textSecondary
                }

                Text {
                    id: titlePlain
                    x: 52
                    y: 14
                    width: Math.max(0, parent.width - 68)
                    text: i18n.t("settings.exportPlainConfig.title")
                    color: Ui.textPrimary
                    font.pixelSize: Ui.textBody
                    font.weight: Ui.weightBold
                    wrapMode: Text.Wrap
                }

                Text {
                    id: descPlain
                    x: 52
                    y: titlePlain.y + titlePlain.implicitHeight + 4
                    width: Math.max(0, parent.width - 68)
                    text: i18n.t("settings.exportPlainConfig.desc")
                    color: Ui.textSecondary
                    font.pixelSize: Ui.textCaption
                    wrapMode: Text.Wrap
                }

                MouseArea {
                    id: mousePlain
                    anchors.fill: parent
                    enabled: !control.enteringPassword
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: {
                        dialog.close()
                        control.exportPlain()
                    }
                }
            }

            TextField {
                id: exportPasswordField
                objectName: "exportPasswordField"
                visible: control.enteringPassword
                overlayField: true
                width: parent.width
                type: "outlined"
                label: i18n.t("settings.exportEncryptedConfig.passwordLabel")
                isPassword: true
                text: control.passwordText
                onTextChanged: control.passwordText = text
                onAccepted: control.submitEncrypted()
            }

            Button {
                id: backBtn
                visible: control.enteringPassword
                y: exportPasswordField.height + Ui.space3
                width: (parent.width - Ui.space2) / 2
                height: 44
                type: "text"
                text: i18n.t("common.back")
                onClicked: {
                    control.passwordText = ""
                    control.enteringPassword = false
                }
            }

            Button {
                id: confirmExportBtn
                objectName: "exportEncryptedConfirmButton"
                visible: control.enteringPassword
                x: backBtn.width + Ui.space2
                y: backBtn.y
                width: (parent.width - Ui.space2) / 2
                height: 44
                type: "filled"
                text: i18n.t("settings.exportEncryptedConfig.confirm")
                enabled: control.passwordText.length > 0
                onClicked: control.submitEncrypted()
            }
        }
    }
}
