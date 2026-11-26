import Foundation
import GoogleSignIn
import MemoCore
import UIKit

extension AppModel {
    /// Backup export: the file is made when the save dialog opens.
    func startBackupExport() {
        backupToExport = backupDocument()
        if backupToExport != nil {
            exportingBackup = true
        }
    }

    func startBackupImport() {
        importingBackup = true
    }

    /// A sheet was swiped away: a page choice stops the import, photos taken so far are dropped.
    func dialogDismissed(_ item: DialogItem) {
        guard dialog?.id == item.id else {
            return
        }
        switch item.kind {
        case .pages(let source, _, _):
            pagesCancelled(source)
        case .photoMore:
            takenPhotos.removeAll()
            dialog = nil
        default:
            dialog = nil
        }
    }

    /// Invite links (memoeverything://join/CODE and the join page) and Google sign-in replies.
    func openURL(_ url: URL) {
        if GIDSignIn.sharedInstance.handle(url) {
            return
        }
        guard let code = EnsembleRules.inviteCode(url), Ensemble.available else {
            return
        }
        ensembleInvite = code
        ensembleOpen = true
    }

    func choosePhotoSource() {
        choose(L("create_import_photo"), [L("photo_take"), L("photo_pick")], selected: nil) { which in
            if which == 0 {
                self.takenPhotos.removeAll()
                self.takePhoto()
            } else {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                    self.pickingPhotos = true
                }
            }
        }
    }

    func takePhoto() {
        guard UIImagePickerControllerAvailability.cameraAvailable else {
            notifyKey("photo_camera_missing")
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
            self.takingPhoto = true
        }
    }

    /// What the camera practice screen needs to know about the scene.
    func videoSetup() -> VideoSetup {
        let cast = category.hasRoles ? currentCast() : [:]
        return VideoSetup(title: scriptTitle, lines: scene.lines, role: role, hasRoles: category.hasRoles,
                          language: language, cast: cast, defaultVoice: defaultVoice(language),
                          aiVoice: useAiVoice, speed: readingSpeed)
    }

    func openVideo() {
        cancelSpeech()
        stopListening()
        videoOpen = true
    }
}

enum UIImagePickerControllerAvailability {
    static var cameraAvailable: Bool {
        UIImagePickerController.isSourceTypeAvailable(.camera)
    }
}
