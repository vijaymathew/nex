;;; nex-mode-setup.el --- Setup script to fix nex-mode configuration -*- lexical-binding: t; -*-

;; This script forcibly configures Emacs to use nex-mode for .nex files
;; and prevents other modes (like clojure-mode) from interfering.

;;; Usage:
;;
;; Option 1: Evaluate this file directly
;;   M-x eval-buffer (while viewing this file)
;;
;; Option 2: Add to your init.el
;;   (load-file "/path/to/nex/editor/emacs/nex-mode-setup.el")

;;; Code:

;; First, load nex-mode
(let ((nex-mode-path (expand-file-name "nex-mode.el"
                                       (file-name-directory
                                        (or load-file-name buffer-file-name)))))
  (if (file-exists-p nex-mode-path)
      (progn
        (load-file nex-mode-path)
        (message "✓ nex-mode loaded from: %s" nex-mode-path))
    (error "Cannot find nex-mode.el at: %s" nex-mode-path)))

;; Remove any conflicting auto-mode-alist entries
(setq auto-mode-alist
      (seq-filter (lambda (entry)
                   (not (and (stringp (car entry))
                            (string-match-p "\\.nex" (car entry))
                            (not (eq (cdr entry) 'nex-mode)))))
                 auto-mode-alist))

;; Add nex-mode association (will be at the front of the list)
(add-to-list 'auto-mode-alist '("\\.nex\\'" . nex-mode))

;; Add magic mode for nex files (checks file content, not just extension)
(add-to-list 'magic-mode-alist '("\\`\\s-*\\(?:--.*\n\\)*\\s-*class\\s-+[A-Z]" . nex-mode))

;; Hook to force nex-mode if we detect a .nex file
(defun force-nex-mode-for-nex-files ()
  "Force nex-mode if current buffer is a .nex file but not in nex-mode."
  (when (and buffer-file-name
             (string-match-p "\\.nex\\'" buffer-file-name)
             (not (eq major-mode 'nex-mode)))
    (nex-mode)
    (message "Switched to nex-mode for %s" (buffer-name))))

;; Add the hook to find-file-hook with high priority
(add-hook 'find-file-hook 'force-nex-mode-for-nex-files)

;; Verify setup
(message "\n=== Nex Mode Setup Complete ===")
(message "Auto-mode-alist entries for .nex:")
(dolist (entry auto-mode-alist)
  (when (and (stringp (car entry))
             (string-match-p "\\.nex" (car entry)))
    (message "  %s -> %s" (car entry) (cdr entry))))

;; Helper function to fix current buffer if it's a .nex file
(defun nex-mode-fix-current-buffer ()
  "Fix the current buffer by switching to nex-mode if it's a .nex file."
  (interactive)
  (if (and buffer-file-name
           (string-match-p "\\.nex\\'" buffer-file-name))
      (progn
        (nex-mode)
        (message "Switched to nex-mode. Try C-c C-z now!"))
    (message "This is not a .nex file")))

(message "\nIf you're currently in a .nex buffer, run: M-x nex-mode-fix-current-buffer")
(message "Or simply run: M-x nex-mode")

(provide 'nex-mode-setup)
;;; nex-mode-setup.el ends here
