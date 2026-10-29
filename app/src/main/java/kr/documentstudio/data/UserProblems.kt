package kr.documentstudio.data

import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import android.system.OsConstants
import java.io.FileNotFoundException
import java.io.IOException

data class UserProblem(val code: String,val title: String,val advice: String)
object UserProblems {
    /** Never display arbitrary exception messages, paths, URI values or document text. */
    fun explain(error: Throwable): UserProblem {
        val causes=generateSequence(error){it.cause}.take(8).toList()
        return when {
            causes.any {it is SignatureStoreUnavailable}->UserProblem("SIGNATURE_RECOVERY","서명 보관함을 확인하지 못했습니다","기존 서명은 보존했습니다. 저장 복구에서 자료를 내보내거나 이전 정상 보관함을 복원하세요.")
            causes.any {it is OriginalChanged}->UserProblem("ORIGINAL_CHANGED","원본이 다른 앱에서 변경되었습니다","덮어쓰기를 중단했습니다. 편집 내용은 다른 이름으로 저장한 뒤 원본과 비교하세요.")
            causes.any {it is SaveRecoveryRequired}->UserProblem("SAVE_INTERRUPTED","이전 저장을 확인해야 합니다","저장 복구에서 이전 원본과 최신 편집 사본을 새 파일로 보관하세요. 원본은 자동으로 덮어쓰지 않습니다.")
            causes.any {it is ProviderWriteFailure}->UserProblem("SAVE_FAILED","파일 저장을 완료하지 못했습니다","원본 반영 여부를 확인해야 합니다. 편집 내용과 저장 복구 자료를 보존했습니다. 접근 권한·여유 공간을 확인하거나 다른 이름으로 저장하세요.")
            causes.any {it is SecurityException}->UserProblem("PERMISSION","파일 접근 권한이 없습니다","파일 선택에서 해당 문서를 다시 선택하세요. 현재 편집 내용이 있다면 다른 이름으로 저장할 수 있습니다.")
            causes.any {it is SQLiteFullException || it is ErrnoException && it.errno==OsConstants.ENOSPC}->UserProblem("STORAGE_FULL","기기 저장 공간이 부족합니다","저장되지 않은 문서나 복구 자료를 지우지 말고 다른 파일을 정리한 뒤 다시 시도하세요.")
            causes.any {it is FileNotFoundException}->UserProblem("MISSING","파일을 찾거나 열지 못했습니다","파일이 이동·삭제되었거나 클라우드에서 내려받지 못했을 수 있습니다. 위치와 접근 권한을 확인한 뒤 다시 선택하세요.")
            causes.any {it is OutOfMemoryError}->UserProblem("MEMORY","문서를 처리할 메모리가 부족합니다","다른 문서를 닫고 다시 시도하세요. 원본과 이미 보관한 복구 자료는 삭제하지 않습니다.")
            causes.any {it is java.util.zip.ZipException || it is org.json.JSONException}->UserProblem("DAMAGED","파일 구조를 확인하지 못했습니다","손상되었거나 이 앱에서 처리하지 못하는 요소가 있을 수 있습니다. 원본을 보존하고 호환 앱에서도 확인하세요.")
            causes.any {it is IllegalArgumentException}->UserProblem("UNSUPPORTED","파일이나 편집 설정을 적용하지 못했습니다","이 형식에서 지원하는 기능과 파일 크기를 확인하세요. 원본과 복구 자료를 유지한 상태로 다시 선택할 수 있습니다.")
            causes.any {it is IOException}->UserProblem("IO","파일 읽기·쓰기를 완료하지 못했습니다","접근 권한과 저장 공간을 확인하세요. 저장 실패 시 다른 이름으로 저장하거나 저장 복구에서 사본을 보관하세요.")
            else->UserProblem("UNEXPECTED","작업을 완료하지 못했습니다","현재 작업을 유지했습니다. 다시 시도하기 전에 저장 상태를 확인하고 중요한 내용은 사본으로 보관하세요.")
        }
    }
}
