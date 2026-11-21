using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace Tandem.Server.Backup;

/// <summary>
/// Optional encryption of a backup file (<c>….tar.gz.enc</c>).
/// <para>Header: the 5 ASCII bytes <c>ESBK1</c>, a random 16-byte salt, and the chunk size as a 32-bit big-endian
/// integer (1048576). Key: PBKDF2-HMAC-SHA256 of the UTF-8 passphrase, 600 000 iterations, 32 bytes.</para>
/// <para>Body: the plaintext cut into 1 MiB chunks, each sealed with AES-256-GCM (no associated data) and written as
/// its ciphertext followed by its 16-byte tag. The 12-byte nonce is 4 zero bytes plus the chunk counter (from 0) as a
/// 64-bit big-endian integer. The last chunk (0 to 1 MiB of plaintext; there is always one) has the counter's high
/// bit set, so a file cut at a chunk boundary fails authentication instead of reading as complete.</para>
/// </summary>
public static class BackupCrypto
{
    public static ReadOnlySpan<byte> Magic => "ESBK1"u8;
    public const int SaltSize = 16;
    public const int ChunkSize = 1048576;
    public const int TagSize = 16;
    public const int NonceSize = 12;
    public const int KeySize = 32;
    public const int Iterations = 600_000;
    public const int HeaderSize = 5 + SaltSize + 4;
    private const ulong FinalFlag = 1UL << 63;
    /// <summary>Largest chunk size a reader accepts from a header (a guard against absurd allocations).</summary>
    private const int MaxChunkSize = 64 * 1024 * 1024;

    public static byte[] DeriveKey(string passphrase, ReadOnlySpan<byte> salt) =>
        Rfc2898DeriveBytes.Pbkdf2(Encoding.UTF8.GetBytes(passphrase), salt, Iterations, HashAlgorithmName.SHA256, KeySize);

    /// <summary>A write-only stream that encrypts into <paramref name="inner"/>. Call
    /// <see cref="EncryptingStream.FinishAsync"/> to write the final chunk; disposing without it leaves the output
    /// unterminated (so a failed run can never produce a file that authenticates as complete).</summary>
    public static EncryptingStream Encrypt(Stream inner, string passphrase) => new(inner, passphrase);

    /// <summary>A read-only stream that decrypts <paramref name="inner"/>; reads throw <see cref="BackupException"/> on a
    /// wrong passphrase, a damaged chunk or a missing final chunk.</summary>
    public static Stream Decrypt(Stream inner, string passphrase) => new DecryptingStream(inner, passphrase);

    /// <summary>Whether <paramref name="prefix"/> (at least 5 bytes) starts like an encrypted backup.</summary>
    public static bool HasMagic(ReadOnlySpan<byte> prefix) => prefix.Length >= Magic.Length && prefix[..Magic.Length].SequenceEqual(Magic);

    private static void Nonce(Span<byte> nonce, ulong counter, bool final)
    {
        nonce[..4].Clear();
        BinaryPrimitives.WriteUInt64BigEndian(nonce[4..], final ? counter | FinalFlag : counter);
    }

    public sealed class EncryptingStream : Stream
    {
        private readonly Stream _inner;
        private readonly AesGcm _aes;
        private readonly byte[] _plain = new byte[ChunkSize];
        private readonly byte[] _sealed = new byte[ChunkSize + TagSize];
        private byte[]? _header;
        private int _filled;
        private ulong _counter;
        private bool _finished;

        internal EncryptingStream(Stream inner, string passphrase)
        {
            _inner = inner;
            var salt = RandomNumberGenerator.GetBytes(SaltSize);
            _aes = new AesGcm(DeriveKey(passphrase, salt), TagSize);
            _header = new byte[HeaderSize];
            Magic.CopyTo(_header);
            salt.CopyTo(_header, Magic.Length);
            BinaryPrimitives.WriteInt32BigEndian(_header.AsSpan(Magic.Length + SaltSize), ChunkSize);
        }

        public override bool CanRead => false;
        public override bool CanSeek => false;
        public override bool CanWrite => !_finished;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override void Flush() { } // whole chunks only: nothing to flush early
        public override Task FlushAsync(CancellationToken ct) => Task.CompletedTask;

        public override void Write(byte[] buffer, int offset, int count) => Write(buffer.AsSpan(offset, count));

        public override void Write(ReadOnlySpan<byte> data)
        {
            ObjectDisposedException.ThrowIf(_finished, this);
            while (!data.IsEmpty)
            {
                // A full chunk is sealed only once more data arrives, so the final chunk may be a full one.
                if (_filled == ChunkSize) _inner.Write(Seal(false));
                var n = Math.Min(data.Length, ChunkSize - _filled);
                data[..n].CopyTo(_plain.AsSpan(_filled));
                _filled += n;
                data = data[n..];
            }
        }

        public override Task WriteAsync(byte[] buffer, int offset, int count, CancellationToken ct) =>
            WriteAsync(buffer.AsMemory(offset, count), ct).AsTask();

        public override async ValueTask WriteAsync(ReadOnlyMemory<byte> data, CancellationToken ct = default)
        {
            ObjectDisposedException.ThrowIf(_finished, this);
            while (!data.IsEmpty)
            {
                if (_filled == ChunkSize) await _inner.WriteAsync(SealMemory(false), ct);
                var n = Math.Min(data.Length, ChunkSize - _filled);
                data[..n].CopyTo(_plain.AsMemory(_filled));
                _filled += n;
                data = data[n..];
            }
        }

        /// <summary>Seals the final chunk and flushes the inner stream. Nothing can be written after it.</summary>
        public async Task FinishAsync(CancellationToken ct)
        {
            ObjectDisposedException.ThrowIf(_finished, this);
            await _inner.WriteAsync(SealMemory(true), ct);
            _finished = true;
            await _inner.FlushAsync(ct);
        }

        private ReadOnlyMemory<byte> SealMemory(bool final)
        {
            var length = SealInto(final);
            if (_header is null) return _sealed.AsMemory(0, length);
            // The header goes out with the first chunk.
            var first = new byte[_header.Length + length];
            _header.CopyTo(first, 0);
            _sealed.AsSpan(0, length).CopyTo(first.AsSpan(_header.Length));
            _header = null;
            return first;
        }

        private ReadOnlySpan<byte> Seal(bool final) => SealMemory(final).Span;

        private int SealInto(bool final)
        {
            Span<byte> nonce = stackalloc byte[NonceSize];
            Nonce(nonce, _counter, final);
            _aes.Encrypt(nonce, _plain.AsSpan(0, _filled), _sealed.AsSpan(0, _filled), _sealed.AsSpan(_filled, TagSize));
            var length = _filled + TagSize;
            _counter++;
            _filled = 0;
            return length;
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                _aes.Dispose();
                _finished = true;
            }
            base.Dispose(disposing);
        }
    }

    private sealed class DecryptingStream(Stream inner, string passphrase) : Stream
    {
        private AesGcm? _aes;
        private int _chunkSize;
        private byte[] _current = [];
        private byte[] _next = [];
        private int _nextLength = -1; // -1: not read yet
        private byte[] _plain = [];
        private int _plainPos;
        private int _plainLength;
        private ulong _counter;
        private bool _done;

        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override void Flush() { }

        public override int Read(byte[] buffer, int offset, int count) => Read(buffer.AsSpan(offset, count));

        public override int Read(Span<byte> buffer)
        {
            while (_plainPos == _plainLength)
            {
                if (_done) return 0;
                if (_aes is null) Start(ReadAtLeast);
                else Advance(ReadAtLeast);
            }
            return Take(buffer);
        }

        public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken ct) =>
            ReadAsync(buffer.AsMemory(offset, count), ct).AsTask();

        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken ct = default)
        {
            while (_plainPos == _plainLength)
            {
                if (_done) return 0;
                if (_aes is null)
                {
                    var header = new byte[HeaderSize];
                    var n = await inner.ReadAtLeastAsync(header, HeaderSize, throwOnEndOfStream: false, ct);
                    StartWith(header, n);
                }
                if (_nextLength < 0)
                    _nextLength = await inner.ReadAtLeastAsync(_next, _next.Length, throwOnEndOfStream: false, ct);
                (_current, _next) = (_next, _current);
                var currentLength = _nextLength;
                _nextLength = await inner.ReadAtLeastAsync(_next, _next.Length, throwOnEndOfStream: false, ct);
                Open(currentLength, final: _nextLength == 0);
            }
            return Take(buffer.Span);
        }

        private int ReadAtLeast(byte[] buffer) => inner.ReadAtLeast(buffer, buffer.Length, throwOnEndOfStream: false);

        private void Start(Func<byte[], int> read)
        {
            var header = new byte[HeaderSize];
            StartWith(header, read(header));
        }

        private void Advance(Func<byte[], int> read)
        {
            if (_nextLength < 0) _nextLength = read(_next);
            (_current, _next) = (_next, _current);
            var currentLength = _nextLength;
            _nextLength = read(_next);
            Open(currentLength, final: _nextLength == 0);
        }

        private void StartWith(byte[] header, int read)
        {
            if (read < HeaderSize || !HasMagic(header))
                throw new BackupException("This is not an encrypted Tandem backup, or it is truncated.");
            _chunkSize = BinaryPrimitives.ReadInt32BigEndian(header.AsSpan(Magic.Length + SaltSize));
            if (_chunkSize is <= 0 or > MaxChunkSize)
                throw new BackupException("The encrypted backup's header is damaged.");
            _aes = new AesGcm(DeriveKey(passphrase, header.AsSpan(Magic.Length, SaltSize)), TagSize);
            _current = new byte[_chunkSize + TagSize];
            _next = new byte[_chunkSize + TagSize];
            _plain = new byte[_chunkSize];
        }

        /// <summary>Authenticates and decrypts the current chunk. <paramref name="final"/>: nothing follows it.</summary>
        private void Open(int length, bool final)
        {
            if (length < TagSize)
                throw new BackupException(_counter == 0 && length == 0
                    ? "The encrypted backup is truncated: it has no data after the header."
                    : "The encrypted backup is truncated or damaged.");
            Span<byte> nonce = stackalloc byte[NonceSize];
            Nonce(nonce, _counter, final);
            var size = length - TagSize;
            try
            {
                _aes!.Decrypt(nonce, _current.AsSpan(0, size), _current.AsSpan(size, TagSize), _plain.AsSpan(0, size));
            }
            catch (AuthenticationTagMismatchException)
            {
                throw new BackupException(_counter == 0
                    ? "Could not decrypt the backup: the passphrase is wrong, or the file is damaged or truncated."
                    : $"The encrypted backup is truncated or damaged (chunk {_counter} failed authentication).");
            }
            _counter++;
            _plainPos = 0;
            _plainLength = size;
            if (final) _done = true;
        }

        private int Take(Span<byte> buffer)
        {
            var n = Math.Min(buffer.Length, _plainLength - _plainPos);
            _plain.AsSpan(_plainPos, n).CopyTo(buffer);
            _plainPos += n;
            return n;
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing) _aes?.Dispose();
            base.Dispose(disposing);
        }
    }
}
