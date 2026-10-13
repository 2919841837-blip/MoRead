package com.mozhi.reader.ui.genui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.MoodBad
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Nightlight
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.TipsAndUpdates
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * `icon name` 可用的图标。模型常写 lucide / Material 两套名字，这里都收。
 * 只用素材图标库，不用 emoji（项目铁律）；未知名字不显示。
 */
object GenUiIcons {
    private val byName: Map<String, ImageVector> = buildMap {
        fun put(vector: ImageVector, vararg names: String) = names.forEach { put(it, vector) }
        put(Icons.Outlined.Add, "add", "plus")
        put(Icons.Outlined.Remove, "remove", "minus")
        put(Icons.Outlined.Check, "check", "done")
        put(Icons.Outlined.CheckCircle, "check-circle", "check_circle", "circle-check", "success")
        put(Icons.Outlined.Close, "close", "x", "cancel")
        put(Icons.Outlined.Info, "info", "information")
        put(Icons.Outlined.HelpOutline, "help", "help-circle", "question", "circle-help")
        put(Icons.Outlined.WarningAmber, "warning", "alert-triangle", "triangle-alert", "alert")
        put(Icons.Outlined.Lightbulb, "lightbulb", "idea", "bulb")
        put(Icons.Outlined.TipsAndUpdates, "tips", "sparkle-idea")
        put(Icons.Outlined.AutoAwesome, "sparkles", "sparkle", "auto-awesome", "magic", "wand")
        put(Icons.Outlined.Star, "star")
        put(Icons.Outlined.StarBorder, "star-outline", "star-border")
        put(Icons.Outlined.Favorite, "heart", "favorite", "love")
        put(Icons.Outlined.FavoriteBorder, "heart-outline")
        put(Icons.Outlined.Bookmark, "bookmark")
        put(Icons.AutoMirrored.Outlined.MenuBook, "book", "book-open", "menu-book", "reading")
        put(Icons.Outlined.FormatQuote, "quote", "format-quote")
        put(Icons.Outlined.Person, "user", "person", "account")
        put(Icons.Outlined.Groups, "users", "group", "groups", "people")
        put(Icons.Outlined.Psychology, "brain", "psychology", "mind")
        put(Icons.Outlined.Mood, "smile", "mood", "happy")
        put(Icons.Outlined.MoodBad, "frown", "sad", "mood-bad")
        put(Icons.Outlined.Schedule, "clock", "time", "schedule")
        put(Icons.Outlined.History, "history", "rewind")
        put(Icons.Outlined.CalendarMonth, "calendar", "date")
        put(Icons.Outlined.Timeline, "timeline", "git-commit")
        put(Icons.AutoMirrored.Outlined.TrendingUp, "trending-up", "trend-up", "rise")
        put(Icons.AutoMirrored.Outlined.TrendingDown, "trending-down", "trend-down", "fall")
        put(Icons.Outlined.Map, "map")
        put(Icons.Outlined.Explore, "compass", "explore")
        put(Icons.Outlined.Public, "globe", "world", "public")
        put(Icons.Outlined.Flag, "flag", "goal")
        put(Icons.Outlined.Shield, "shield", "guard")
        put(Icons.Outlined.Lock, "lock", "secret")
        put(Icons.Outlined.Bolt, "zap", "bolt", "lightning", "flash")
        put(Icons.Outlined.LocalFireDepartment, "fire", "flame")
        put(Icons.Outlined.Whatshot, "hot", "whatshot")
        put(Icons.Outlined.WbSunny, "sun", "sunny", "day")
        put(Icons.Outlined.Nightlight, "moon", "night")
        put(Icons.Outlined.MusicNote, "music", "music-note", "note")
        put(Icons.AutoMirrored.Outlined.VolumeUp, "volume", "volume-2", "speaker", "audio")
        put(Icons.Outlined.PlayArrow, "play", "play-arrow")
        put(Icons.Outlined.Pause, "pause")
        put(Icons.Outlined.Refresh, "refresh", "rotate-cw", "repeat", "retry")
        put(Icons.Outlined.Search, "search", "magnifier")
        put(Icons.Outlined.Settings, "settings", "gear", "cog")
        put(Icons.Outlined.Palette, "palette", "colors")
        put(Icons.Outlined.Brush, "brush", "paint")
        put(Icons.Outlined.Image, "image", "picture", "photo")
        put(Icons.Outlined.Link, "link")
        put(Icons.Outlined.ContentCopy, "copy", "content-copy", "clipboard")
        put(Icons.Outlined.Home, "home", "house")
        put(Icons.Outlined.Category, "category", "shapes", "layers")
        put(Icons.Outlined.Visibility, "eye", "visible", "visibility")
        put(Icons.Outlined.VisibilityOff, "eye-off", "hidden", "visibility-off")
        put(Icons.Outlined.ChevronRight, "chevron-right", "chevron_right")
        put(Icons.Outlined.ChevronLeft, "chevron-left", "chevron_left")
        put(Icons.Outlined.ExpandMore, "chevron-down", "expand-more", "expand")
        put(Icons.Outlined.ExpandLess, "chevron-up", "expand-less", "collapse")
        put(Icons.AutoMirrored.Outlined.ArrowForward, "arrow-right", "arrow-forward", "next")
        put(Icons.AutoMirrored.Outlined.ArrowBack, "arrow-left", "arrow-back", "back", "previous")
        put(Icons.AutoMirrored.Outlined.Send, "send", "message")
    }

    fun resolve(name: String?): ImageVector? = name?.trim()?.lowercase()?.replace('_', '-')?.let(byName::get)
}
