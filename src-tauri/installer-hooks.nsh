; Preserve USBLink's normal close path, which revokes active USB shares.
; These hooks run before Tauri's default process-killing check.
!macro USBLinkCheckProcess processName
  nsis_tauri_utils::FindProcessCurrentUser "${processName}"
  Pop $R0
  ${If} $R0 = 0
    SetErrorLevel 1618
    Abort "请先正常关闭 USBLink，等待 USB 共享停止后，再进行安装或卸载。"
  ${EndIf}
!macroend

!macro USBLinkRequireClosed
  !insertmacro USBLinkCheckProcess "USBLink.exe"
  ; Portable releases before the session-lock feature used versioned filenames.
  !insertmacro USBLinkCheckProcess "USBLink-0.2.14.exe"
  !insertmacro USBLinkCheckProcess "USBLink-0.2.15.exe"

  ; Versioned portable executables have a different process name but use this lock.
  ${If} ${FileExists} "$LOCALAPPDATA\USBLink\sharing-session.lock"
    System::Call 'kernel32::CreateFileW(w "$LOCALAPPDATA\USBLink\sharing-session.lock", i 0x80000000, i 7, p 0, i 3, i 0, p 0) p.r0'
    ${If} $0 P= -1
      SetErrorLevel 1618
      Abort "USBLink 共享会话仍在使用，请先关闭程序并等待共享停止。"
    ${Else}
      System::Call 'kernel32::CloseHandle(p r0)'
    ${EndIf}
  ${EndIf}
!macroend

!macro NSIS_HOOK_PREINSTALL
  !insertmacro USBLinkRequireClosed
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  !insertmacro USBLinkRequireClosed
!macroend

!macro NSIS_HOOK_POSTINSTALL
  ; Keep an existing auto-start preference, updating a portable/old install path.
  ReadRegStr $0 HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "USBLink"
  ${If} $0 != ""
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "USBLink" '$\"$INSTDIR\USBLink.exe$\"'
  ${EndIf}
!macroend
