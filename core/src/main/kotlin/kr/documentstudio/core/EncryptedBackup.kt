package kr.documentstudio.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Versioned authenticated envelope around the existing bounded ZIP backup. */
object EncryptedBackup {
    private val magic="DSBKP001".toByteArray(Charsets.US_ASCII)
    private const val iterations=600000
    private const val headerSize=40
    const val maximumSize=32L*1024*1024
    fun isEncrypted(file: File): Boolean=file.inputStream().use {readHeader(it,8).contentEquals(magic)}
    fun encrypt(source: File,destination: File,password: CharArray) {
        require(password.size in 8..256) {"백업 암호는 8~256자로 입력하세요."}
        require(source.length() in 1..maximumSize-headerSize-16) {"암호화 백업 크기 제한(32 MiB)을 초과했습니다."}
        val random=SecureRandom();val salt=ByteArray(16).also {random.nextBytes(it)};val nonce=ByteArray(12).also {random.nextBytes(it)}
        val header=ByteBuffer.allocate(headerSize).put(magic).putInt(iterations).put(salt).put(nonce).array()
        crypt(source,destination,password,header,Cipher.ENCRYPT_MODE)
    }
    fun decrypt(source: File,destination: File,password: CharArray) {
        require(password.size in 1..256) {"백업 암호를 입력하세요."}
        require(source.length() in headerSize+16L..maximumSize) {"암호화 백업이 잘렸거나 용량 제한을 초과했습니다."}
        val header=source.inputStream().use {readHeader(it,headerSize)}
        require(header.copyOfRange(0,8).contentEquals(magic)) {"지원하지 않는 암호화 백업 형식입니다."}
        require(ByteBuffer.wrap(header,8,4).int==iterations) {"지원하지 않는 키 유도 설정입니다."}
        try {crypt(source,destination,password,header,Cipher.DECRYPT_MODE)}
        catch(e: GeneralSecurityException) {throw IllegalArgumentException("암호가 틀리거나 백업이 손상되었습니다.",e)}
    }
    private fun readHeader(input: java.io.InputStream,size: Int): ByteArray {
        val bytes=ByteArray(size);var count=0
        while(count<size) {val n=input.read(bytes,count,size-count);if(n<0) break;count+=n}
        return bytes.copyOf(count)
    }
    private fun crypt(source: File,destination: File,password: CharArray,header: ByteArray,mode: Int) {
        require(source.canonicalFile!=destination.canonicalFile)
        val spec=PBEKeySpec(password,header.copyOfRange(12,28),iterations,256)
        val key=try {SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded} finally {spec.clearPassword()}
        val cipher=try {Cipher.getInstance("AES/GCM/NoPadding").apply {init(mode,SecretKeySpec(key,"AES"),GCMParameterSpec(128,header.copyOfRange(28,40)));updateAAD(header)}} finally {key.fill(0)}
        val temporary=File.createTempFile("crypto-stage-",".bin",destination.absoluteFile.parentFile)
        try {
            source.inputStream().use {input->temporary.outputStream().use {out->
                if(mode==Cipher.ENCRYPT_MODE) out.write(header) else require(readHeader(input,headerSize).contentEquals(header))
                val buffer=ByteArray(65536);var total=0L
                while(true) {val n=input.read(buffer);if(n<0) break;total+=n;require(total<=maximumSize);cipher.update(buffer,0,n)?.let(out::write)}
                out.write(cipher.doFinal());out.fd.sync()
            }}
            try {Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}
            catch(_: java.nio.file.AtomicMoveNotSupportedException) {Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING)}
        } finally {temporary.delete()}
    }
}
