using System.IO;

namespace PulseSend.Windows.ViewModels;

public sealed class PendingUploadItemViewModel : ViewModelBase
{
    private readonly Action<PendingUploadItemViewModel>? _removeRequested;

    public PendingUploadItemViewModel(string filePath, Action<PendingUploadItemViewModel>? removeRequested = null)
    {
        FilePath = filePath;
        FileName = Path.GetFileName(filePath);
        SizeText = BuildSizeText(filePath);
        _removeRequested = removeRequested;
        RemoveCommand = new RelayCommand(Remove);
    }

    public string FilePath { get; }

    public string FileName { get; }

    public string SizeText { get; }

    public RelayCommand RemoveCommand { get; }

    private void Remove()
    {
        _removeRequested?.Invoke(this);
    }

    private static string BuildSizeText(string path)
    {
        try
        {
            var info = new FileInfo(path);
            if (!info.Exists)
            {
                return "未知大小";
            }

            var size = info.Length;
            if (size < 1024)
            {
                return $"{size} B";
            }
            var kb = size / 1024d;
            if (kb < 1024)
            {
                return $"{kb:F1} KB";
            }
            var mb = kb / 1024d;
            if (mb < 1024)
            {
                return $"{mb:F1} MB";
            }
            var gb = mb / 1024d;
            return $"{gb:F1} GB";
        }
        catch
        {
            return "未知大小";
        }
    }
}
