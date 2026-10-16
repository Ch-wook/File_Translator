package kr.documentstudio.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EncryptedBackupTest {
    private fun file()=File.createTempFile("encrypted-backup-",".bin").apply {deleteOnExit()}
    @Test fun independentUtf8Pbkdf2GoldenEnvelopeDecrypts() {
        val encoded=javaClass.getResourceAsStream("/backup-golden.b64")!!.use {it.readBytes().toString(Charsets.UTF_8)}
        val source=file().apply {writeBytes(java.util.Base64.getDecoder().decode(encoded))};val output=file()
        EncryptedBackup.decrypt(source,output,"검증 pass 123!".toCharArray());assertEquals("독립 암호화 검증\n",output.readText())
    }
    @Test fun authenticatedUnicodePasswordRoundtripUsesFreshSaltAndNonce() {
        val source=file().apply {writeBytes(ByteArray(200003) {(it%251).toByte()})};val a=file();val b=file();val result=file();val password="한글 암호 1234!".toCharArray()
        EncryptedBackup.encrypt(source,a,password);EncryptedBackup.encrypt(source,b,password)
        assertTrue(EncryptedBackup.isEncrypted(a));assertFalse(a.readBytes().contentEquals(b.readBytes()));EncryptedBackup.decrypt(a,result,password);assertArrayEquals(source.readBytes(),result.readBytes())
        assertTrue(password.contentEquals("한글 암호 1234!".toCharArray()))
    }
    @Test fun wrongPasswordTamperedHeaderCiphertextAndTagNeverReplaceOutput() {
        val source=file().apply {writeText("PRIVATE_BACKUP_CONTENT")};val encrypted=file();val output=file().apply {writeText("KEEP")};val password="password123!".toCharArray()
        EncryptedBackup.encrypt(source,encrypted,password)
        assertThrows(IllegalArgumentException::class.java) {EncryptedBackup.decrypt(encrypted,output,"incorrect".toCharArray())};assertEquals("KEEP",output.readText())
        val bytes=encrypted.readBytes()
        for(index in listOf(12,28,40,bytes.lastIndex)) {
            val altered=file().apply {writeBytes(bytes.copyOf().also {it[index]=(it[index].toInt() xor 1).toByte()})}
            assertThrows(IllegalArgumentException::class.java) {EncryptedBackup.decrypt(altered,output,password)};assertEquals("KEEP",output.readText())
        }
        val truncated=file().apply {writeBytes(bytes.copyOf(30))};assertThrows(IllegalArgumentException::class.java) {EncryptedBackup.decrypt(truncated,output,password)}
        assertEquals("KEEP",output.readText())
    }
}
