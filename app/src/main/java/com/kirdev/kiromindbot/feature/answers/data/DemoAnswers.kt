package com.kirdev.kiromindbot.feature.answers.data

import com.kirdev.kiromindbot.feature.answers.domain.AnswerEntry
import com.kirdev.kiromindbot.feature.answers.domain.AnswerMedia
import com.kirdev.kiromindbot.feature.answers.domain.MediaKind
import com.kirdev.kiromindbot.feature.answers.domain.ReplyStyle
import com.kirdev.kiromindbot.feature.answers.domain.ReplyVariant

/** Demo base inserted once into an empty database on first launch. All media are demo placeholders. */
object DemoAnswers {
    val seed: List<AnswerEntry> = listOf(
        AnswerEntry(1, "Привет", "Привет! Как дела?", favorite = true, alternatives = listOf(
            ReplyVariant("1:1", "Здравствуй!"),
            ReplyVariant("1:2", "Рад тебя видеть!", style = ReplyStyle.FRIENDLY),
            ReplyVariant("1:3", "Здравствуйте! Чем могу помочь?", style = ReplyStyle.POLITE),
            ReplyVariant("1:4", "О, явился! Привет-привет.", style = ReplyStyle.CHEEKY)
        )),
        AnswerEntry(2, "Пришли фото", "Держи фотографию!", listOf(AnswerMedia("m1", MediaKind.PHOTO, "demo_photo.jpg", "demo://photo"))),
        AnswerEntry(3, "Покажи видео", "Вот видео", listOf(AnswerMedia("m2", MediaKind.VIDEO, "demo_video.mp4", "demo://video"))),
        AnswerEntry(4, "Голосовое", "", listOf(AnswerMedia("m3", MediaKind.AUDIO, "demo_voice.ogg", "demo://audio"))),
        AnswerEntry(5, "Инструкция", "Вот файл", listOf(AnswerMedia("m4", MediaKind.DOCUMENT, "manual.pdf", "demo://document"))),
        AnswerEntry(6, "Как дела?", "Отлично! А у тебя?", favorite = true,
            alternatives = listOf(ReplyVariant("6:1", "Всё хорошо, спасибо! А ты как?"))),
        AnswerEntry(7, "Тоже хорошо", "Рад слышать! Чем займёмся?", afterEntryId = 6),
        AnswerEntry(8, "Плохо", "Сочувствую. Хочешь рассказать?", afterEntryId = 6)
    )
}
