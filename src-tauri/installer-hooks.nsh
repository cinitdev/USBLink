; Force-close only after the user explicitly clicks the installer confirmation.
!macro USBLinkCheckProcess processName
  nsis_tauri_utils::FindProcessCurrentUser "${processName}"
  Pop $R0
  ${If} $R0 = 0
    StrCpy $R1 1
  ${EndIf}
!macroend

!macro USBLinkReadBusy
  StrCpy $R1 0
  !insertmacro USBLinkCheckProcess "${MAINBINARYNAME}.exe"
  ; Portable releases before the session-lock feature used versioned filenames.
  !insertmacro USBLinkCheckProcess "${PRODUCTNAME}-0.2.14.exe"
  !insertmacro USBLinkCheckProcess "${PRODUCTNAME}-0.2.15.exe"
  !insertmacro USBLinkCheckProcess "${PRODUCTNAME}-0.2.16.exe"

  ; Versioned portable executables have a different process name but use this lock.
  ${If} ${FileExists} "$LOCALAPPDATA\${PRODUCTNAME}\sharing-session.lock"
    System::Call 'kernel32::CreateFileW(w "$LOCALAPPDATA\${PRODUCTNAME}\sharing-session.lock", i 0x80000000, i 7, p 0, i 3, i 0, p 0) p.r0'
    ${If} $0 P= -1
      StrCpy $R1 1
    ${Else}
      System::Call 'kernel32::CloseHandle(p r0)'
    ${EndIf}
  ${EndIf}
!macroend

!macro USBLinkKillProcess processName
  nsis_tauri_utils::KillProcessCurrentUser "${processName}"
  Pop $R0
!macroend

!macro USBLinkRequireClosed
  !define USBLINK_GUARD_ID ${__LINE__}
  ; Preserve the maintenance page's version and radio controls.
  Push $0
  Push $R0
  Push $R1
  Push $R2
  !insertmacro USBLinkReadBusy
  ${If} $R1 = 1
    DetailPrint "${PRODUCTNAME} 仍在运行，等待用户选择是否结束进程。"
    IfSilent usblink_unattended_${USBLINK_GUARD_ID} 0
    ${If} $PassiveMode = 1
      Goto usblink_unattended_${USBLINK_GUARD_ID}
    ${EndIf}
    MessageBox MB_OKCANCEL|MB_ICONEXCLAMATION|MB_DEFBUTTON2 "结束进程并继续？$\r$\n$\r$\n${PRODUCTNAME} 仍在运行。点击“确定”，安装器将结束 ${PRODUCTNAME} 进程并继续，无需手动关闭。$\r$\n$\r$\n强制结束会跳过 USB 共享和挂载清理；遗留状态将在下次启动 ${PRODUCTNAME} 时清理。$\r$\n$\r$\n点击“取消”退出本次操作。" /SD IDCANCEL IDOK usblink_kill_${USBLINK_GUARD_ID}
    SetErrorLevel 1
    Quit

    usblink_kill_${USBLINK_GUARD_ID}:
    DetailPrint "正在结束 ${PRODUCTNAME} 进程…"
    !insertmacro USBLinkKillProcess "${MAINBINARYNAME}.exe"
    !insertmacro USBLinkKillProcess "${PRODUCTNAME}-0.2.14.exe"
    !insertmacro USBLinkKillProcess "${PRODUCTNAME}-0.2.15.exe"
    !insertmacro USBLinkKillProcess "${PRODUCTNAME}-0.2.16.exe"
    ; A kill request alone is not proof that the executable and lock are free.
    StrCpy $R2 50
    usblink_wait_${USBLINK_GUARD_ID}:
    !insertmacro USBLinkReadBusy
    ${If} $R1 = 0
      DetailPrint "${PRODUCTNAME} 已退出，继续操作。"
      Goto usblink_closed_${USBLINK_GUARD_ID}
    ${EndIf}
    Sleep 100
    IntOp $R2 $R2 - 1
    IntCmp $R2 0 usblink_failed_${USBLINK_GUARD_ID} usblink_failed_${USBLINK_GUARD_ID} usblink_wait_${USBLINK_GUARD_ID}

    usblink_failed_${USBLINK_GUARD_ID}:
    MessageBox MB_RETRYCANCEL|MB_ICONEXCLAMATION|MB_DEFBUTTON2 "未能结束 ${PRODUCTNAME}，或会话文件仍被其他进程占用。尚未继续操作。$\r$\n$\r$\n点击“重试”再次结束 ${PRODUCTNAME} 进程；点击“取消”退出。若权限不足，可用当前账户以管理员身份运行安装器。" /SD IDCANCEL IDRETRY usblink_kill_${USBLINK_GUARD_ID}
    SetErrorLevel 1
    Quit

    usblink_unattended_${USBLINK_GUARD_ID}:
    SetErrorLevel 1618
    Quit
  ${EndIf}
  usblink_closed_${USBLINK_GUARD_ID}:
  Pop $R2
  Pop $R1
  Pop $R0
  Pop $0
  !undef USBLINK_GUARD_ID
!macroend

!macro NSIS_HOOK_PREINSTALL
  !insertmacro USBLinkRequireClosed
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  !insertmacro USBLinkRequireClosed
!macroend

!macro NSIS_HOOK_POSTINSTALL
  ; Keep an existing auto-start preference, updating a portable/old install path.
  ReadRegStr $0 HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "${PRODUCTNAME}"
  ${If} $0 != ""
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "${PRODUCTNAME}" '$\"$INSTDIR\${MAINBINARYNAME}.exe$\"'
  ${EndIf}
!macroend
