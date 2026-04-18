' Silent launcher: no console window
Set fso = CreateObject("Scripting.FileSystemObject")
scriptDir = fso.GetParentFolderName(WScript.ScriptFullName)
Set sh = CreateObject("WScript.Shell")
sh.CurrentDirectory = scriptDir
sh.Run "javaw -jar """ & scriptDir & "\CquptVpn.jar""", 0, False
