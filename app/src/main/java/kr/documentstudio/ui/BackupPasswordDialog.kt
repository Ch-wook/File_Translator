package kr.documentstudio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

@Composable
fun BackupPasswordDialog(create: Boolean,busy: Boolean,error: String?=null,onConfirm: (CharArray)->Unit,onDismiss: ()->Unit) {
    var password by remember {mutableStateOf("")};var repeat by remember {mutableStateOf("")}
    fun clear() {password="";repeat=""}
    val valid=if(create) password.length in 8..256 && password==repeat else password.isNotEmpty()
    AlertDialog(onDismissRequest={if(!busy) {clear();onDismiss()}},title={Text(if(create) "암호화 백업" else "백업 암호 입력")},text={Column {
        Text(if(create) "문서·필기·복구 자료를 암호화합니다. 암호는 저장하지 않으며 잊으면 복구할 수 없습니다. 암호화 백업은 최대 32 MiB입니다." else "암호를 입력하면 무결성을 확인한 뒤 새 복구 문서로 가져옵니다.")
        OutlinedTextField(password,{password=it.take(256)},label={Text("백업 암호")},singleLine=true,enabled=!busy,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
        if(create) OutlinedTextField(repeat,{repeat=it.take(256)},label={Text("암호 다시 입력")},singleLine=true,enabled=!busy,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),isError=repeat.isNotEmpty() && password!=repeat)
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }},confirmButton={TextButton({val value=password.toCharArray();clear();onConfirm(value)},enabled=valid && !busy) {Text(if(create) "백업 파일 선택" else "복구")}},dismissButton={TextButton({clear();onDismiss()},enabled=!busy) {Text("취소")}})
}
