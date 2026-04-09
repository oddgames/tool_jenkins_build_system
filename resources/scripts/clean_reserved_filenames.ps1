Add-Type @'
using System;
using System.Runtime.InteropServices;

public class Win32ReservedFileCleaner {
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern IntPtr FindFirstFileW(string lpFileName, out WIN32_FIND_DATA lpFindFileData);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern bool FindNextFileW(IntPtr hFindFile, out WIN32_FIND_DATA lpFindFileData);
    [DllImport("kernel32.dll", SetLastError=true)]
    static extern bool FindClose(IntPtr hFindFile);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    public static extern bool DeleteFileW(string lpFileName);

    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
    public struct WIN32_FIND_DATA {
        public uint dwFileAttributes;
        public System.Runtime.InteropServices.ComTypes.FILETIME ftCreationTime;
        public System.Runtime.InteropServices.ComTypes.FILETIME ftLastAccessTime;
        public System.Runtime.InteropServices.ComTypes.FILETIME ftLastWriteTime;
        public uint nFileSizeHigh;
        public uint nFileSizeLow;
        public uint dwReserved0;
        public uint dwReserved1;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst=260)]
        public string cFileName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst=14)]
        public string cAlternateFileName;
    }

    static readonly IntPtr INVALID = new IntPtr(-1);
    static readonly string[] RESERVED = {"CON","PRN","AUX","NUL",
        "COM1","COM2","COM3","COM4","COM5","COM6","COM7","COM8","COM9",
        "LPT1","LPT2","LPT3","LPT4","LPT5","LPT6","LPT7","LPT8","LPT9"};

    static bool IsReserved(string name) {
        string noExt = name.Contains(".") ? name.Substring(0, name.IndexOf('.')) : name;
        return Array.Exists(RESERVED, r => r.Equals(noExt, StringComparison.OrdinalIgnoreCase));
    }

    public static int CleanDir(string dir) {
        int count = 0;
        ScanDir(@"\\?\" + dir, ref count);
        return count;
    }

    static void ScanDir(string path, ref int count) {
        WIN32_FIND_DATA fd;
        IntPtr h = FindFirstFileW(path + @"\*", out fd);
        if (h == INVALID) return;
        do {
            if (fd.cFileName == "." || fd.cFileName == "..") continue;
            string full = path + @"\" + fd.cFileName;
            if ((fd.dwFileAttributes & 0x10) != 0) {
                ScanDir(full, ref count);
            } else if (IsReserved(fd.cFileName)) {
                Console.WriteLine("Removing reserved filename: " + full.Replace(@"\\?\",""));
                DeleteFileW(full);
                count++;
            }
        } while (FindNextFileW(h, out fd));
        FindClose(h);
    }
}
'@

$n = [Win32ReservedFileCleaner]::CleanDir($args[0])
if ($n -eq 0) { Write-Host "No reserved filenames found." }
else { Write-Host "Removed $n reserved file(s)." }
