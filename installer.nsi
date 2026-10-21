Unicode true
!include "MUI2.nsh"
Name "AWR Download Next 2.2 Turbo"
OutFile "release\AWR-Download-Next-2.2.0-Setup-x64.exe"
InstallDir "$LOCALAPPDATA\Programs\AWRDownloadNext"
RequestExecutionLevel user
SetCompressor /SOLID lzma
!define MUI_ICON "awr.ico"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"
!insertmacro MUI_LANGUAGE "Arabic"
Section
 SetOutPath "$INSTDIR"
 File /r "dist\*"
 WriteUninstaller "$INSTDIR\Uninstall.exe"
 CreateShortcut "$DESKTOP\AWR Download Next.lnk" "$INSTDIR\AWRDownloadNext.exe"
 CreateDirectory "$SMPROGRAMS\AWR Download Next"
 CreateShortcut "$SMPROGRAMS\AWR Download Next\AWR Download Next.lnk" "$INSTDIR\AWRDownloadNext.exe"
 WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AWRDownloadNext" "DisplayName" "AWR Download Next 2.2 Turbo"
 WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AWRDownloadNext" "UninstallString" '"$INSTDIR\Uninstall.exe"'
 WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AWRDownloadNext" "DisplayVersion" "2.2.0"
 WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AWRDownloadNext" "NoModify" 1
 WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AWRDownloadNext" "NoRepair" 1
SectionEnd
Section "Uninstall"
 Delete "$INSTDIR\AWRDownloadNext.exe"
 Delete "$INSTDIR\README.md"
 Delete "$INSTDIR\WINDOWS-TEST-REPORT.txt"
 RMDir /r "$INSTDIR\Engine"
 RMDir /r "$INSTDIR\Source"
 Delete "$INSTDIR\Uninstall.exe"
 RMDir "$INSTDIR"
 Delete "$DESKTOP\AWR Download Next.lnk"
 RMDir /r "$SMPROGRAMS\AWR Download Next"
 DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AWRDownloadNext"
SectionEnd
