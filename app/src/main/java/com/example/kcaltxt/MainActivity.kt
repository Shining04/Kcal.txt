package com.example.kcaltxt

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

// ──────────────────────────────────────────────
// 1. 데이터 모델
// ──────────────────────────────────────────────

/** UI 에서 사용하는 개별 음식 아이템 */
data class FoodItem(
    val emoji: String,
    val name: String,
    val calories: Int
)

/** 하나의 식사 기록 */
data class DietRecord(
    val id: String = UUID.randomUUID().toString(),
    val rawText: String,
    val foods: List<FoodItem> = emptyList(),
    val totalCalories: Int = 0,
    val aiComment: String = "",
    val isAnalyzing: Boolean = true
)

data class UiState(
    val records: List<DietRecord> = emptyList(),
    val dailyCalories: Int = 0,
    val inputText: String = "",
    val isLoading: Boolean = false,
    val maxKcal: Int = 2000,
    val showGoalDialog: Boolean = false,
    val showHistory: Boolean = false,
    val history: Map<String, List<DietRecord>> = emptyMap()
)

// ──────────────────────────────────────────────
// 2. Gemini 응답 JSON 모델 (Gson 파싱용)
// ──────────────────────────────────────────────

/**
 * Gemini 가 반환할 JSON 구조:
 * {
 *   "total_kcal": 320,
 *   "items": [ { "name": "구운 식빵 1장", "kcal": 100, "emoji": "🍞" } ],
 *   "ai_comment": "바쁜 하루였네요, 잘하셨어요!"
 * }
 */
data class GeminiResponse(
    @SerializedName("total_kcal") val totalKcal: Int,
    @SerializedName("items") val items: List<GeminiItem>,
    @SerializedName("ai_comment") val aiComment: String = ""
)

data class GeminiItem(
    @SerializedName("name") val name: String,
    @SerializedName("kcal") val kcal: Int,
    @SerializedName("emoji") val emoji: String = "🍽️"
)

// ──────────────────────────────────────────────
// 3. ViewModel — Gemini API 연동
// ──────────────────────────────────────────────

class DietViewModel(application: Application) : AndroidViewModel(application) {

    // ── SharedPreferences ──
    private val prefs = application.getSharedPreferences("kcaltxt_prefs", Context.MODE_PRIVATE)
    private companion object {
        const val KEY_RECORDS = "diet_records"
        const val KEY_MAX_KCAL = "max_kcal"
        const val KEY_LAST_DATE = "last_date"
        const val KEY_HISTORY = "history_records"
    }

    /**
     * Gemini GenerativeModel 초기화.
     *
     * ── 핵심 설정 ──
     * - modelName: "gemini-2.5-flash" (빠르고 저렴)
     * - responseMimeType: "application/json" → JSON 만 응답하도록 강제
     * - systemInstruction: 영양 AI 역할 부여 + 출력 형식 지정
     */
    private val generativeModel = GenerativeModel(
        modelName = "gemini-2.5-flash",
        apiKey = GEMINI_API_KEY,
        generationConfig = generationConfig {
            responseMimeType = "application/json"
        },
        systemInstruction = content {
            text(
                """
                너는 사용자의 식단 일기를 분석하는 다정하고 전문적인 영양 코치야.
                1) 텍스트에서 음식명과 대략적인 칼로리를 추출해.
                2) 각 음식에 어울리는 이모지를 emoji 필드에 넣어줘.
                3) 사용자의 기분이나 상황(바쁨, 우울함, 기쁨 등)에 공감하는 따뜻하고 위로가 되는 한국어 코멘트(1~2문장)를 ai_comment 에 반드시 작성해.
                결과는 무조건 아래 JSON 형식으로만 반환해. 다른 텍스트는 절대 포함하지 마.
                
                {
                  "total_kcal": 전체칼로리합계,
                  "items": [
                    { "name": "음식이름", "kcal": 칼로리, "emoji": "이모지" }
                  ],
                  "ai_comment": "따뜻한 한마디"
                }
                
                예시 입력: "바빠서 편의점 삼각김밥 두 개로 때웠다"
                예시 출력: {"total_kcal": 400, "items": [{"name": "삼각김밥", "kcal": 200, "emoji": "🍙"}, {"name": "삼각김밥", "kcal": 200, "emoji": "🍙"}], "ai_comment": "바쁜 와중에도 꼬박꼬박 챙겨 드시는 거 정말 대단해요. 내일은 따뜻한 국물 한 그릇 어때요? 😊"}
                """.trimIndent()
            )
        }
    )

    private val gson = Gson()

    var uiState by mutableStateOf(UiState())
        private set

    // ── 초기화: 날짜 비교 + 저장소 복원 ──
    init {
        val today = LocalDate.now().toString()            // "2026-02-26"
        val lastDate = prefs.getString(KEY_LAST_DATE, today) ?: today
        val savedMaxKcal = prefs.getInt(KEY_MAX_KCAL, 2000)
        val savedHistory = loadHistory()

        if (lastDate != today) {
            // 날짜가 바뀌었으면 → 어제 기록을 히스토리로 아카이브
            val oldRecords = loadRecords()
            if (oldRecords.isNotEmpty()) {
                savedHistory[lastDate] = oldRecords
            }
            // 오늘 기록은 빈 상태로 시작
            uiState = UiState(
                records = emptyList(),
                dailyCalories = 0,
                maxKcal = savedMaxKcal,
                history = savedHistory.toMap()
            )
            // 저장소 반영
            prefs.edit()
                .putString(KEY_LAST_DATE, today)
                .putString(KEY_RECORDS, "[]")
                .putString(KEY_HISTORY, gson.toJson(savedHistory))
                .apply()
        } else {
            // 같은 날 → 기존 기록 복원
            val savedRecords = loadRecords()
            uiState = UiState(
                records = savedRecords,
                dailyCalories = savedRecords.filter { !it.isAnalyzing }.sumOf { it.totalCalories },
                maxKcal = savedMaxKcal,
                history = savedHistory.toMap()
            )
            // lastDate 저장 (최초 설치 시)
            if (!prefs.contains(KEY_LAST_DATE)) {
                prefs.edit().putString(KEY_LAST_DATE, today).apply()
            }
        }
    }

    private fun loadRecords(): List<DietRecord> {
        val json = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<DietRecord>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.e("DietViewModel", "기록 복원 실패", e)
            emptyList()
        }
    }

    private fun loadHistory(): MutableMap<String, List<DietRecord>> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return mutableMapOf()
        return try {
            val type = object : TypeToken<MutableMap<String, List<DietRecord>>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.e("DietViewModel", "히스토리 복원 실패", e)
            mutableMapOf()
        }
    }

    private fun saveState() {
        prefs.edit()
            .putString(KEY_RECORDS, gson.toJson(uiState.records.filter { !it.isAnalyzing }))
            .putInt(KEY_MAX_KCAL, uiState.maxKcal)
            .apply()
    }

    fun onInputChange(text: String) {
        uiState = uiState.copy(inputText = text)
    }

    fun showGoalDialog() {
        uiState = uiState.copy(showGoalDialog = true)
    }

    fun dismissGoalDialog() {
        uiState = uiState.copy(showGoalDialog = false)
    }

    fun updateMaxKcal(newMax: Int) {
        uiState = uiState.copy(maxKcal = newMax.coerceIn(500, 10000), showGoalDialog = false)
        saveState()
    }

    fun showHistory() {
        uiState = uiState.copy(showHistory = true)
    }

    fun dismissHistory() {
        uiState = uiState.copy(showHistory = false)
    }

    /**
     * 사용자 입력 → Gemini API 호출 → JSON 파싱 → DietRecord 생성.
     */
    fun analyzeInput() {
        val text = uiState.inputText.trim()
        if (text.isEmpty()) return

        val placeholder = DietRecord(rawText = text)
        uiState = uiState.copy(
            records = listOf(placeholder) + uiState.records,
            inputText = "",
            isLoading = true
        )

        viewModelScope.launch {
            try {
                val response = generativeModel.generateContent(text)
                val jsonText = response.text ?: throw Exception("빈 응답")

                Log.d("DietViewModel", "Gemini 응답: $jsonText")

                val parsed = gson.fromJson(jsonText, GeminiResponse::class.java)

                val foodItems = parsed.items.map { item ->
                    FoodItem(
                        emoji = item.emoji,
                        name = item.name,
                        calories = item.kcal
                    )
                }

                val completed = placeholder.copy(
                    foods = foodItems,
                    totalCalories = parsed.totalKcal,
                    aiComment = parsed.aiComment,
                    isAnalyzing = false
                )
                updateRecord(placeholder.id, completed)

            } catch (e: Exception) {
                Log.e("DietViewModel", "Gemini 분석 실패", e)
                val errorRecord = placeholder.copy(
                    foods = listOf(FoodItem("⚠️", "AI 분석 실패: ${e.message?.take(100) ?: "알 수 없는 오류"}", 0)),
                    isAnalyzing = false
                )
                updateRecord(placeholder.id, errorRecord)
            }
        }
    }

    /** placeholder → 완성된 record 교체 + 총 칼로리 재계산 + 저장 */
    private fun updateRecord(placeholderId: String, newRecord: DietRecord) {
        val updatedList = uiState.records.map {
            if (it.id == placeholderId) newRecord else it
        }
        uiState = uiState.copy(
            records = updatedList,
            dailyCalories = updatedList
                .filter { !it.isAnalyzing }
                .sumOf { it.totalCalories },
            isLoading = false
        )
        saveState()
    }

    /** 기록 삭제 + 총 칼로리 재계산 + 저장 */
    fun deleteRecord(id: String) {
        val updatedList = uiState.records.filter { it.id != id }
        uiState = uiState.copy(
            records = updatedList,
            dailyCalories = updatedList
                .filter { !it.isAnalyzing }
                .sumOf { it.totalCalories }
        )
        saveState()
    }
}

// ──────────────────────────────────────────────
// 4. Activity
// ──────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: DietViewModel = viewModel()
            KcalApp(vm)
        }
    }
}

// ──────────────────────────────────────────────
// 5. 디자인 토큰
// ──────────────────────────────────────────────

// 배경 그라데이션 (새벽빛 파스텔)
private val GradientTop = Color(0xFFE8EDF4)     // 새벽의 연한 푸른빛
private val GradientMid = Color(0xFFF3EDE6)     // 따뜻한 피치 베이지
private val GradientBot = Color(0xFFF7F6F3)     // 아이보리

private val CardWhite = Color(0xFFFFFFFF)
private val GlassWhite = Color(0xCCFFFFFF)      // 반투명 글래스모피즘
private val GlassBorder = Color(0x33FFFFFF)     // 유리 테두리
private val TextBlack = Color(0xFF2D2D2D)
private val TextDarkGray = Color(0xFF555555)
private val TextGray = Color(0xFF999999)
private val TextLightGray = Color(0xFFBBBBBB)
private val DividerColor = Color(0xFFEDEDED)
private val ChipBg = Color(0xFFF4F2EF)
private val Accent = Color(0xFF6B9E78)          // 가든 그린
private val AccentSoft = Color(0xFFE8F0EA)      // 연한 가든 그린 배경
private val CommentColor = Color(0xFF7A8B6F)    // 올리브 그린
private val RingTrack = Color(0xFFE8E8E8)       // 링 배경

// ──────────────────────────────────────────────
// 6. Composable UI — 프리미엄 감성 다이어리
// ──────────────────────────────────────────────

@Composable
fun KcalApp(vm: DietViewModel) {
    val state = vm.uiState
    val uriHandler = LocalUriHandler.current

    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(GradientTop, GradientMid, GradientBot),
                        startY = 0f,
                        endY = 1200f
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                GardenHeader(
                    totalCalories = state.dailyCalories,
                    maxKcal = state.maxKcal,
                    onEditGoal = { vm.showGoalDialog() }
                )

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item { Spacer(modifier = Modifier.height(4.dp)) }

                    items(items = state.records, key = { it.id }) { record ->
                        MealCard(
                            record = record,
                            onDelete = { vm.deleteRecord(record.id) }
                        )
                    }

                    if (state.records.isEmpty()) {
                        item { EmptyState() }
                    }

                    item { Spacer(modifier = Modifier.height(4.dp)) }
                }

                InputBar(
                    text = state.inputText,
                    isLoading = state.isLoading,
                    onTextChange = vm::onInputChange,
                    onSend = { vm.analyzeInput() }
                )
            }

            // ── 상단 우측 버튼들 (히스토리 + 커피) ──
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(innerPadding)
                    .padding(top = 6.dp, end = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 📅 과거 기록
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { vm.showHistory() }
                ) {
                    Text(
                        text = "📅",
                        fontSize = 22.sp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(CardWhite.copy(alpha = 0.7f))
                            .padding(6.dp)
                    )
                    Text(
                        text = "기록",
                        color = TextLightGray,
                        fontSize = 9.sp,
                        letterSpacing = (-0.2).sp
                    )
                }
                // ☕ 후원
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { uriHandler.openUri("https://buymeacoffee.com/shining_s") }
                ) {
                    Text(
                        text = "☕",
                        fontSize = 22.sp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(CardWhite.copy(alpha = 0.7f))
                            .padding(6.dp)
                    )
                    Text(
                        text = "후원",
                        color = TextLightGray,
                        fontSize = 9.sp,
                        letterSpacing = (-0.2).sp
                    )
                }
            }
        }
    }

    // ── 목표 칼로리 수정 다이얼로그 ──
    if (state.showGoalDialog) {
        GoalKcalDialog(
            currentGoal = state.maxKcal,
            onDismiss = { vm.dismissGoalDialog() },
            onConfirm = { vm.updateMaxKcal(it) }
        )
    }

    // ── 과거 기록 다이얼로그 ──
    if (state.showHistory) {
        HistoryDialog(
            history = state.history,
            onDismiss = { vm.dismissHistory() }
        )
    }
}

// ── 정원 헤더 (글래스모피즘 카드 + kcal 기반 게이지) ──

@Composable
fun GardenHeader(totalCalories: Int, maxKcal: Int, onEditGoal: () -> Unit) {
    val ratio = if (maxKcal > 0) (totalCalories.toFloat() / maxKcal).coerceIn(0f, 1f) else 0f
    val percent = (ratio * 100).toInt()

    val gardenEmoji = when {
        percent >= 100 -> "🌸"
        percent >= 70  -> "🌷"
        percent >= 30  -> "🌿"
        else           -> "🌱"
    }
    val gardenLabel = when {
        percent >= 100 -> "오늘의 꽃이 활짝 피었어요!"
        percent >= 70  -> "꽃봉오리가 맺혔어요"
        percent >= 30  -> "잎사귀가 무럭무럭 자라요"
        else           -> "새싹이 돋았어요"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        // 글래스모피즘 카드
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(24.dp),
                    ambientColor = Accent.copy(alpha = 0.08f),
                    spotColor = Accent.copy(alpha = 0.06f)
                )
                .clip(RoundedCornerShape(24.dp))
                .background(GlassWhite)
                .border(
                    width = 1.dp,
                    color = GlassBorder,
                    shape = RoundedCornerShape(24.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 원형 진행도 + 이모지
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { 1f },
                        modifier = Modifier.size(80.dp),
                        color = RingTrack,
                        strokeWidth = 4.dp,
                        strokeCap = StrokeCap.Round
                    )
                    CircularProgressIndicator(
                        progress = { ratio },
                        modifier = Modifier.size(80.dp),
                        color = Accent,
                        strokeWidth = 4.dp,
                        strokeCap = StrokeCap.Round
                    )
                    Text(gardenEmoji, fontSize = 32.sp)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 정원 라벨
                Text(
                    text = gardenLabel,
                    color = Accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.3).sp
                )

                Spacer(modifier = Modifier.height(6.dp))

                // 칼로리 (클릭하면 목표 수정)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onEditGoal() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "오늘",
                        color = TextGray,
                        fontSize = 12.sp,
                        letterSpacing = (-0.2).sp
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "$totalCalories",
                        color = TextDarkGray,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.5).sp
                    )
                    Text(
                        text = " / $maxKcal",
                        color = TextLightGray,
                        fontSize = 13.sp,
                        letterSpacing = (-0.3).sp
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "kcal",
                        color = TextGray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "✏️",
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

// ── 목표 칼로리 다이얼로그 ──

@Composable
private fun GoalKcalDialog(
    currentGoal: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var text by remember { mutableStateOf(currentGoal.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = "🌱 목표 칼로리 설정",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextBlack,
                letterSpacing = (-0.3).sp
            )
        },
        text = {
            Column {
                Text(
                    text = "하루 목표 칼로리를 입력해 주세요",
                    color = TextGray,
                    fontSize = 13.sp,
                    letterSpacing = (-0.2).sp
                )
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { newVal ->
                        text = newVal.filter { it.isDigit() }.take(5)
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    suffix = { Text("kcal", color = TextGray, fontSize = 14.sp) },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = DividerColor,
                        cursorColor = Accent
                    ),
                    modifier = Modifier.widthIn(max = 200.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = text.toIntOrNull() ?: currentGoal
                    onConfirm(value)
                }
            ) {
                Text("확인", color = Accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소", color = TextGray)
            }
        }
    )
}

// ── 과거 기록 다이얼로그 ──

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryDialog(
    history: Map<String, List<DietRecord>>,
    onDismiss: () -> Unit
) {
    // 날짜 내림차순 정렬
    val sortedDates = remember(history) {
        history.keys.sortedDescending()
    }
    val expandedDates = remember { mutableStateMapOf<String, Boolean>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = "📅 지난 기록",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextBlack,
                letterSpacing = (-0.3).sp
            )
        },
        text = {
            if (sortedDates.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp)
                ) {
                    Text("🌿", fontSize = 28.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "아직 지난 기록이 없어요",
                        color = TextLightGray,
                        fontSize = 14.sp,
                        letterSpacing = (-0.2).sp
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.height(360.dp)
                ) {
                    items(sortedDates) { dateStr ->
                        val records = history[dateStr] ?: emptyList()
                        val totalKcal = records.sumOf { it.totalCalories }
                        val isExpanded = expandedDates[dateStr] == true

                        // 날짜를 한국어로 포맷 (2026-02-25 → 2월 25일)
                        val displayDate = try {
                            val ld = LocalDate.parse(dateStr)
                            "${ld.monthValue}월 ${ld.dayOfMonth}일"
                        } catch (_: Exception) { dateStr }

                        val dayEmoji = when {
                            totalKcal >= 2000 -> "🌸"
                            totalKcal >= 1200 -> "🌿"
                            totalKcal >= 500  -> "🌱"
                            else              -> "🫧"
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    expandedDates[dateStr] = !isExpanded
                                }
                                .background(if (isExpanded) AccentSoft else Color.Transparent)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(dayEmoji, fontSize = 18.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = displayDate,
                                    color = TextBlack,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = (-0.2).sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${totalKcal} kcal",
                                    color = Accent,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = (-0.3).sp
                                )
                            }

                            // 확장 영역: 음식 + AI 코멘트
                            AnimatedVisibility(visible = isExpanded) {
                                Column(modifier = Modifier.padding(top = 10.dp, start = 26.dp)) {
                                    records.forEach { record ->
                                        // AI 코멘트
                                        if (record.aiComment.isNotBlank()) {
                                            Text(
                                                text = record.aiComment,
                                                color = CommentColor,
                                                fontSize = 12.sp,
                                                fontStyle = FontStyle.Italic,
                                                lineHeight = 18.sp,
                                                letterSpacing = (-0.1).sp
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                        }
                                        // 음식 칩
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            record.foods.forEach { food ->
                                                Text(
                                                    text = "${food.emoji} ${food.name}",
                                                    color = TextDarkGray,
                                                    fontSize = 12.sp,
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(ChipBg)
                                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                    }
                                }
                            }
                        }

                        if (dateStr != sortedDates.last()) {
                            HorizontalDivider(
                                color = DividerColor,
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기", color = Accent, fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

// ── 빈 화면 ──

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 40.dp, bottom = 40.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            // 큰 장식 아이콘
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(AccentSoft),
                contentAlignment = Alignment.Center
            ) {
                Text("🌷", fontSize = 44.sp)
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "아직 피어나지 않은 오늘 하루,",
                color = TextGray,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = (-0.3).sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "어떤 음식으로 채우셨나요?",
                color = TextDarkGray,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.3).sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "먹은 것을 편하게 적어 주세요.\nAI가 따뜻하게 분석해 드릴게요.",
                color = TextLightGray,
                fontSize = 13.sp,
                lineHeight = 22.sp,
                letterSpacing = (-0.1).sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ── 식사 카드 ──

@Composable
fun MealCard(record: DietRecord, onDelete: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = Color.Black.copy(alpha = 0.06f),
                spotColor = Color.Black.copy(alpha = 0.04f)
            )
            .clip(RoundedCornerShape(20.dp))
            .background(CardWhite)
    ) {
        Column(modifier = Modifier.padding(22.dp)) {
            // ── 상단: 원문 + 삭제 버튼 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = record.rawText,
                    color = TextBlack,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal,
                    lineHeight = 26.sp,
                    letterSpacing = (-0.3).sp,
                    modifier = Modifier.weight(1f)
                )
                if (!record.isAnalyzing) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(28.dp)
                            .padding(start = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "삭제",
                            tint = TextLightGray,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (record.isAnalyzing) {
                Spacer(modifier = Modifier.height(16.dp))
                AnalyzingIndicator()
            } else {
                // ── AI 코멘트 ──
                if (record.aiComment.isNotBlank()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = record.aiComment,
                        color = CommentColor,
                        fontSize = 13.sp,
                        fontStyle = FontStyle.Italic,
                        lineHeight = 22.sp,
                        letterSpacing = (-0.2).sp
                    )
                }

                // ── 음식 칩 ──
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(14.dp))
                FoodChips(foods = record.foods)
            }
        }
    }
}

@Composable
private fun AnalyzingIndicator() {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val dotAlpha by pulse.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot"
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .alpha(
                        (dotAlpha + i * 0.2f).coerceIn(0f, 1f)
                    )
                    .clip(CircleShape)
                    .background(Accent)
            )
            if (i < 2) Spacer(modifier = Modifier.width(4.dp))
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "정원에 물을 주고 있어요…",
            color = TextLightGray,
            fontSize = 12.sp,
            letterSpacing = (-0.2).sp
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoodChips(foods: List<FoodItem>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        foods.forEach { food ->
            FoodChip(food)
        }
    }
}

@Composable
private fun FoodChip(food: FoodItem) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ChipBg)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = food.emoji, fontSize = 14.sp)
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = food.name,
            color = TextDarkGray,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.2).sp
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = "${food.calories}kcal",
            color = Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.2).sp
        )
    }
}

@Composable
fun InputBar(
    text: String,
    isLoading: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(GradientBot)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(CardWhite)
                    .border(
                        width = 1.dp,
                        color = DividerColor,
                        shape = RoundedCornerShape(22.dp)
                    )
                    .padding(horizontal = 18.dp, vertical = 13.dp)
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = if (isLoading) "정원에 물을 주고 있어요…" else "오늘 하루, 뭘 드셨나요?",
                        color = if (isLoading) Accent.copy(alpha = 0.6f) else TextLightGray,
                        fontSize = 15.sp,
                        letterSpacing = (-0.2).sp
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = TextStyle(
                        color = TextBlack,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        letterSpacing = (-0.2).sp
                    ),
                    cursorBrush = SolidColor(Accent),
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            IconButton(
                onClick = onSend,
                enabled = text.isNotBlank() && !isLoading,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Accent,
                    contentColor = Color.White,
                    disabledContainerColor = if (isLoading) Accent else Accent.copy(alpha = 0.25f),
                    disabledContentColor = Color.White.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                        strokeCap = StrokeCap.Round
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "전송",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}