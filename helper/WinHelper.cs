// MyWisper helper: tells the app which window is in front and what text is selected.
// Build: npm run build:helper  (uses the C# compiler that ships with Windows)
//
// Protocol: one command per line on stdin, one JSON line per answer on stdout.
//   "fg <id>"   → {"id":1,"process":"chrome","title":"Inbox - Gmail","x":0,"y":0,"w":1536,"h":816}
//   "sel <id>"  → {"id":2,"supported":true,"text":"the selected text","editable":true}
using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Automation;
using System.Windows.Automation.Text;

class WinHelper
{
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int max);
    [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")] static extern bool SetProcessDpiAwarenessContext(IntPtr value);
    struct RECT { public int Left, Top, Right, Bottom; }

    static readonly object OutLock = new object();

    static void Main()
    {
        // Report real (physical) pixels, same as the keyboard/mouse hook in the main app.
        try { SetProcessDpiAwarenessContext(new IntPtr(-4)); } catch { }
        Console.OutputEncoding = new UTF8Encoding(false);
        Console.InputEncoding = new UTF8Encoding(false);

        string line;
        while ((line = Console.ReadLine()) != null)
        {
            string[] parts = line.Trim().Split(' ');
            string cmd = parts[0];
            string id = parts.Length > 1 ? parts[1] : "0";
            try
            {
                if (cmd == "fg") Write(Foreground(id));
                else if (cmd == "sel") Write(Selection(id));
                else if (cmd == "mask") { TapMaskKey(); Write("{\"id\":" + id + ",\"ok\":true}"); }
                else if (cmd == "quit") return;
                else Write("{\"id\":" + id + ",\"error\":\"unknown command\"}");
            }
            catch (Exception e)
            {
                Write("{\"id\":" + id + ",\"error\":" + Json(e.Message) + "}");
            }
        }
    }

    // "mask": taps virtual key 0xE8, which Windows leaves unassigned. Tapped while Win is held, it stops
    // the Start menu from opening when Win is released, without triggering any real shortcut.
    // (F24 must not be used: Win + Ctrl + F24 is Windows' touchpad on/off shortcut.)
    [DllImport("user32.dll", SetLastError = true)] static extern uint SendInput(uint count, INPUT[] inputs, int size);
    [StructLayout(LayoutKind.Sequential)] struct KEYBDINPUT { public ushort wVk, wScan; public uint dwFlags, time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)] struct MOUSEINPUT { public int dx, dy; public uint mouseData, dwFlags, time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Explicit)] struct INPUTUNION { [FieldOffset(0)] public MOUSEINPUT mi; [FieldOffset(0)] public KEYBDINPUT ki; }
    [StructLayout(LayoutKind.Sequential)] struct INPUT { public uint type; public INPUTUNION u; }
    const ushort VK_UNASSIGNED = 0xE8;
    const uint INPUT_KEYBOARD = 1, KEYEVENTF_KEYUP = 0x0002;

    static void TapMaskKey()
    {
        var down = new INPUT { type = INPUT_KEYBOARD };
        down.u.ki.wVk = VK_UNASSIGNED;
        var up = down;
        up.u.ki.dwFlags = KEYEVENTF_KEYUP;
        SendInput(2, new[] { down, up }, Marshal.SizeOf(typeof(INPUT)));
    }

    static string Foreground(string id)
    {
        IntPtr h = GetForegroundWindow();
        uint pid;
        GetWindowThreadProcessId(h, out pid);
        var title = new StringBuilder(512);
        GetWindowText(h, title, 512);
        RECT r;
        GetWindowRect(h, out r);
        string process = "";
        try { process = Process.GetProcessById((int)pid).ProcessName; } catch { }
        return "{\"id\":" + id + ",\"process\":" + Json(process) + ",\"title\":" + Json(title.ToString()) +
               ",\"x\":" + r.Left + ",\"y\":" + r.Top + ",\"w\":" + (r.Right - r.Left) + ",\"h\":" + (r.Bottom - r.Top) + "}";
    }

    // Reads the selection with UI Automation. Some apps hang or don't support it, so this runs
    // on its own thread with a time limit; "supported":false means "use Ctrl+C instead".
    // "editable" says whether keyboard focus is in a field you can type in (text box, editor, compose box),
    // so the Rewrite pill doesn't pop up for text selected on a web page, a button, a file list…
    static string Selection(string id)
    {
        string text = null;
        bool supported = false;
        bool? editable = null; // null = UI Automation couldn't tell; the caret decides
        var worker = new Thread(() =>
        {
            try
            {
                AutomationElement el = AutomationElement.FocusedElement;
                if (el == null) return;
                object pattern;
                if (el.TryGetCurrentPattern(ValuePattern.Pattern, out pattern))
                    editable = !((ValuePattern)pattern).Current.IsReadOnly;
                else if (el.Current.ControlType == ControlType.Edit)
                    editable = true;
                if (el.TryGetCurrentPattern(TextPattern.Pattern, out pattern))
                {
                    var sb = new StringBuilder();
                    TextPatternRange[] ranges = ((TextPattern)pattern).GetSelection();
                    foreach (TextPatternRange range in ranges)
                        sb.Append(range.GetText(-1));
                    text = sb.ToString();
                    supported = true;
                    // Rich editors (contenteditable in browsers: Gmail, Slack, ChatGPT…) have no ValuePattern,
                    // but their text reports whether it is read-only.
                    if (editable == null)
                    {
                        TextPatternRange r = ranges.Length > 0 ? ranges[0] : ((TextPattern)pattern).DocumentRange;
                        object ro = r.GetAttributeValue(TextPattern.IsReadOnlyAttribute);
                        if (ro is bool) editable = !(bool)ro;
                    }
                }
            }
            catch { }
        });
        worker.IsBackground = true;
        worker.Start();
        bool finished = worker.Join(400);
        // Editors (Notepad, Word, RichEdit) and Chromium/Electron text fields show a system caret;
        // web page text, buttons and lists don't.
        bool isEditable = (finished ? editable : null) ?? HasCaret();
        string edit = ",\"editable\":" + (isEditable ? "true" : "false");
        if (!finished) return "{\"id\":" + id + ",\"supported\":false,\"timeout\":true" + edit + "}";
        return "{\"id\":" + id + ",\"supported\":" + (supported ? "true" : "false") + ",\"text\":" + Json(text ?? "") + edit + "}";
    }

    [StructLayout(LayoutKind.Sequential)]
    struct GUITHREADINFO
    {
        public int cbSize, flags;
        public IntPtr hwndActive, hwndFocus, hwndCapture, hwndMenuOwner, hwndMoveSize, hwndCaret;
        public RECT rcCaret;
    }
    [DllImport("user32.dll")] static extern bool GetGUIThreadInfo(uint threadId, ref GUITHREADINFO info);

    static bool HasCaret()
    {
        uint pid;
        uint thread = GetWindowThreadProcessId(GetForegroundWindow(), out pid);
        var info = new GUITHREADINFO { cbSize = Marshal.SizeOf(typeof(GUITHREADINFO)) };
        return thread != 0 && GetGUIThreadInfo(thread, ref info) && info.hwndCaret != IntPtr.Zero;
    }

    static void Write(string json)
    {
        lock (OutLock) { Console.WriteLine(json); Console.Out.Flush(); }
    }

    static string Json(string s)
    {
        var sb = new StringBuilder("\"");
        foreach (char c in s)
        {
            switch (c)
            {
                case '"': sb.Append("\\\""); break;
                case '\\': sb.Append("\\\\"); break;
                case '\n': sb.Append("\\n"); break;
                case '\r': sb.Append("\\r"); break;
                case '\t': sb.Append("\\t"); break;
                default:
                    if (c < 0x20) sb.AppendFormat("\\u{0:x4}", (int)c);
                    else sb.Append(c);
                    break;
            }
        }
        return sb.Append('"').ToString();
    }
}
