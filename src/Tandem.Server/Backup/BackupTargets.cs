using Amazon;
using Amazon.Runtime;
using Amazon.S3;
using Amazon.S3.Model;
using Tandem.Protocol;

namespace Tandem.Server.Backup;

/// <summary>
/// Where backups go. Every method deals only in backup names (<see cref="BackupFormat.IsBackupName"/>): other files at
/// the target are never listed, overwritten or deleted.
/// </summary>
public interface IBackupTarget
{
    /// <summary>Reads <paramref name="content"/> to its end and stores it as <paramref name="name"/>. The name only
    /// appears once the whole stream was stored; if reading or storing fails, nothing is left under it.</summary>
    Task WriteAsync(string name, Stream content, CancellationToken ct);

    /// <summary>The backup names present, in no particular order.</summary>
    Task<IReadOnlyList<string>> ListAsync(CancellationToken ct);

    Task DeleteAsync(string name, CancellationToken ct);

    Task<Stream> OpenReadAsync(string name, CancellationToken ct);
}

public static class BackupTargets
{
    /// <summary>The target the settings describe; <see cref="BackupException"/> when they do not describe one.</summary>
    public static IBackupTarget Create(BackupSettingsDto settings) => settings.Kind switch
    {
        BackupTargetKind.Folder when !string.IsNullOrWhiteSpace(settings.FolderPath) => new FolderTarget(settings.FolderPath),
        BackupTargetKind.S3 when settings.S3 is { } s3 && !string.IsNullOrEmpty(s3.SecretKey) => new S3BackupTarget(s3),
        _ => throw new BackupException("No backup target is configured."),
    };
}

/// <summary>A mounted folder (NAS). It must already exist: a missing mount is an error, not a reason to write into the
/// container's own disk.</summary>
public sealed class FolderTarget(string folder) : IBackupTarget
{
    private void RequireFolder()
    {
        if (!Directory.Exists(folder))
            throw new BackupException($"The backup folder {folder} does not exist (is the share mounted?).");
    }

    public async Task WriteAsync(string name, Stream content, CancellationToken ct)
    {
        BackupFormat.RequireName(name);
        RequireFolder();
        var final = Path.Combine(folder, name);
        // Hidden and not matching the backup pattern, so a half-written file is never listed or kept.
        var partial = Path.Combine(folder, $".{name}.partial-{Guid.NewGuid():N}");
        var moved = false;
        try
        {
            await using (var file = new FileStream(partial, FileMode.CreateNew, FileAccess.Write, FileShare.None, 1 << 20,
                             FileOptions.Asynchronous))
            {
                await content.CopyToAsync(file, 1 << 20, ct);
                await file.FlushAsync(ct);
                file.Flush(flushToDisk: true);
            }
            File.Move(partial, final, overwrite: true);
            moved = true;
        }
        finally
        {
            if (!moved)
            {
                try { File.Delete(partial); } catch (IOException) { } catch (UnauthorizedAccessException) { }
            }
        }
    }

    public Task<IReadOnlyList<string>> ListAsync(CancellationToken ct)
    {
        RequireFolder();
        IReadOnlyList<string> names = Directory.EnumerateFiles(folder).Select(Path.GetFileName).Where(BackupFormat.IsBackupName)
            .Select(n => n!).ToList();
        return Task.FromResult(names);
    }

    public Task DeleteAsync(string name, CancellationToken ct)
    {
        BackupFormat.RequireName(name);
        File.Delete(Path.Combine(folder, name));
        return Task.CompletedTask;
    }

    public Task<Stream> OpenReadAsync(string name, CancellationToken ct)
    {
        BackupFormat.RequireName(name);
        Stream stream = new FileStream(Path.Combine(folder, name), FileMode.Open, FileAccess.Read, FileShare.Read, 1 << 20,
            FileOptions.Asynchronous | FileOptions.SequentialScan);
        return Task.FromResult(stream);
    }
}

/// <summary>
/// An S3 bucket (AWS, or any S3-compatible service via <see cref="S3Target.Endpoint"/>, path-style). The archive is
/// streamed as a multipart upload: one part is buffered in memory at a time (8 MiB, growing for very large backups
/// to stay under the 10 000-part limit), nothing is staged on disk. A failed upload is aborted, so no object appears.
/// </summary>
public sealed class S3BackupTarget : IBackupTarget
{
    private const int BasePartSize = 8 * 1024 * 1024;
    private readonly S3Target _target;
    private readonly string _prefix;

    public S3BackupTarget(S3Target target)
    {
        _target = target;
        var prefix = (target.Prefix ?? "").Trim('/');
        _prefix = prefix.Length == 0 ? "" : prefix + "/";
    }

    private AmazonS3Client Client()
    {
        var config = new AmazonS3Config
        {
            // Only send/expect checksums when an operation needs them: many S3-compatible services lack the newer ones.
            RequestChecksumCalculation = RequestChecksumCalculation.WHEN_REQUIRED,
            ResponseChecksumValidation = ResponseChecksumValidation.WHEN_REQUIRED,
        };
        var region = string.IsNullOrWhiteSpace(_target.Region) ? "us-east-1" : _target.Region;
        if (!string.IsNullOrWhiteSpace(_target.Endpoint))
        {
            config.ServiceURL = _target.Endpoint;
            config.ForcePathStyle = true;
            config.AuthenticationRegion = region;
        }
        else
        {
            config.RegionEndpoint = RegionEndpoint.GetBySystemName(region);
        }
        return new AmazonS3Client(new BasicAWSCredentials(_target.AccessKey, _target.SecretKey), config);
    }

    private string Key(string name) => _prefix + name;

    /// <summary>Part n (1-based): 8 MiB for parts 1-2000, doubling every 2000 parts (about 496 GB in 10 000 parts).</summary>
    private static int PartSize(int partNumber) => BasePartSize << Math.Min(4, (partNumber - 1) / 2000);

    public async Task WriteAsync(string name, Stream content, CancellationToken ct)
    {
        BackupFormat.RequireName(name);
        using var s3 = Client();
        var key = Key(name);
        var upload = await s3.InitiateMultipartUploadAsync(new InitiateMultipartUploadRequest { BucketName = _target.Bucket, Key = key }, ct);
        var parts = new List<PartETag>();
        try
        {
            byte[]? buffer = null;
            for (var partNumber = 1; ; partNumber++)
            {
                if (partNumber > 10_000) throw new BackupException("The backup is too large for one S3 multipart upload.");
                var size = PartSize(partNumber);
                if (buffer is null || buffer.Length != size) buffer = new byte[size];
                var read = await content.ReadAtLeastAsync(buffer, size, throwOnEndOfStream: false, ct);
                if (read == 0 && parts.Count > 0) break;
                using var part = new MemoryStream(buffer, 0, read, writable: false);
                var response = await s3.UploadPartAsync(new UploadPartRequest
                {
                    BucketName = _target.Bucket, Key = key, UploadId = upload.UploadId, PartNumber = partNumber,
                    PartSize = read, InputStream = part,
                }, ct);
                parts.Add(new PartETag(partNumber, response.ETag));
                if (read < size) break;
            }
            await s3.CompleteMultipartUploadAsync(new CompleteMultipartUploadRequest
            {
                BucketName = _target.Bucket, Key = key, UploadId = upload.UploadId, PartETags = parts,
            }, ct);
        }
        catch
        {
            try
            {
                await s3.AbortMultipartUploadAsync(new AbortMultipartUploadRequest
                    { BucketName = _target.Bucket, Key = key, UploadId = upload.UploadId }, CancellationToken.None);
            }
            catch (Exception) { /* the original failure is the one to report; the bucket's lifecycle can expire it */ }
            throw;
        }
    }

    public async Task<IReadOnlyList<string>> ListAsync(CancellationToken ct)
    {
        using var s3 = Client();
        var names = new List<string>();
        var request = new ListObjectsV2Request { BucketName = _target.Bucket, Prefix = _prefix };
        while (true)
        {
            var response = await s3.ListObjectsV2Async(request, ct);
            foreach (var o in response.S3Objects ?? [])
            {
                var name = o.Key[_prefix.Length..];
                if (BackupFormat.IsBackupName(name)) names.Add(name); // never anything below a deeper "/"
            }
            if (response.IsTruncated != true) break;
            request.ContinuationToken = response.NextContinuationToken;
        }
        return names;
    }

    public async Task DeleteAsync(string name, CancellationToken ct)
    {
        BackupFormat.RequireName(name);
        using var s3 = Client();
        await s3.DeleteObjectAsync(new DeleteObjectRequest { BucketName = _target.Bucket, Key = Key(name) }, ct);
    }

    public async Task<Stream> OpenReadAsync(string name, CancellationToken ct)
    {
        BackupFormat.RequireName(name);
        var s3 = Client();
        try
        {
            var response = await s3.GetObjectAsync(new GetObjectRequest { BucketName = _target.Bucket, Key = Key(name) }, ct);
            return new OwningStream(response.ResponseStream, response, s3);
        }
        catch
        {
            s3.Dispose();
            throw;
        }
    }

    /// <summary>The response body; disposing it also disposes the response and the client.</summary>
    private sealed class OwningStream(Stream inner, params IDisposable[] owned) : Stream
    {
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override void Flush() { }
        public override int Read(byte[] buffer, int offset, int count) => inner.Read(buffer, offset, count);
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken ct = default) => inner.ReadAsync(buffer, ct);

        public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken ct) =>
            inner.ReadAsync(buffer, offset, count, ct);

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                inner.Dispose();
                foreach (var o in owned) o.Dispose();
            }
            base.Dispose(disposing);
        }
    }
}
