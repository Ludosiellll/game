package forma.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.ui.theme.F
import forma.app.ui.theme.Ic
import forma.app.ui.theme.T
import forma.app.ui.theme.labelText
import forma.app.ui.theme.numText

/** Card surface in the current look. */
@Composable
fun FCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    outline: Color? = null,
    fill: Color? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    gap: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(F.shape.radius)
    val border = when {
        outline != null -> BorderStroke(if (F.shape.border > 0.dp) F.shape.border else 1.dp, outline)
        F.shape.border > 0.dp -> BorderStroke(F.shape.border, F.c.line)
        F.c.dark -> BorderStroke(1.dp, F.c.line)
        else -> null
    }
    var m = modifier.fillMaxWidth().clip(shape).background(fill ?: F.c.surface)
    if (border != null) m = m.border(border, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(padding), verticalArrangement = Arrangement.spacedBy(gap), content = content)
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = F.c.accent,
    textColor: Color = F.c.onAccent,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 54.dp,
) {
    val shape = if (F.shape.pill) RoundedCornerShape(50) else RoundedCornerShape(F.shape.radiusSmall)
    var m = modifier.heightIn(min = height).clip(shape).background(if (enabled) color else F.c.surface2)
    if (F.shape.border > 0.dp) m = m.border(F.shape.border, F.c.line, shape)
    Row(
        m.clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) textColor else F.c.text3, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            if (F.type.capsTitles) text.uppercase() else text,
            style = T.body(16.sp, FontWeight.ExtraBold).copy(
                color = if (enabled) textColor else F.c.text3,
                fontFamily = if (F.type.capsTitles) F.type.display else F.type.text,
                fontWeight = if (F.type.capsTitles) FontWeight.Black else FontWeight.ExtraBold,
                fontSize = if (F.type.capsTitles) 28.sp else 16.sp,
            ),
            maxLines = 1,
        )
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = F.c.text, height: Dp = 44.dp) {
    val shape = if (F.shape.pill) RoundedCornerShape(50) else RoundedCornerShape(F.shape.radiusSmall)
    Box(
        modifier.heightIn(min = height).clip(shape)
            .border(if (F.shape.border > 0.dp) F.shape.border else 1.dp, if (F.shape.border > 0.dp) F.c.line else F.c.line, shape)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(labelText(text), style = T.body(14.sp, FontWeight.Bold).copy(color = color), textAlign = TextAlign.Center)
    }
}

/** Round icon button with a description for screen readers. */
@Composable
fun RoundIcon(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp, fill: Color? = null, tint: Color = F.c.text) {
    val shape = if (F.shape.pill) CircleShape else RoundedCornerShape(F.shape.radiusSmall)
    var m = modifier.size(size).clip(shape)
    m = if (fill != null) m.background(fill) else m.border(if (F.shape.border > 0.dp) F.shape.border else 1.dp, F.c.line, shape)
    Box(m.clickable(role = Role.Button, onClickLabel = description, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(size * 0.45f))
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, minWidth: Dp = 44.dp) {
    val shape = if (F.shape.pill) RoundedCornerShape(50) else RoundedCornerShape(F.shape.radiusSmall)
    val bg = if (selected) F.c.text else Color.Transparent
    val fg = if (selected) F.c.bg else F.c.text
    Box(
        modifier.heightIn(min = 40.dp).widthIn(min = minWidth).clip(shape).background(bg)
            .border(if (F.shape.border > 0.dp) F.shape.border else 1.dp, if (selected) F.c.text else F.c.line, shape)
            .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        val st = T.number(15.sp, FontWeight.Bold).copy(color = fg)
        Text(numText(text, st), style = st, maxLines = 1)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = F.c.text2) {
    Text(labelText(text), style = T.label().copy(color = color), modifier = modifier)
}

@Composable
fun StatTile(label: String, value: String, sub: String?, color: Color, modifier: Modifier = Modifier) {
    FCard(modifier, padding = PaddingValues(12.dp), gap = 2.dp, fill = F.c.surface) {
        Text(label, style = T.secondary(12.sp), maxLines = 1)
        val st = T.number(24.sp).copy(color = color)
        Text(numText(value, st), style = st, maxLines = 1)
        if (sub != null) Text(sub, style = T.secondary(11.sp).copy(color = F.c.text3), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Value with − and + buttons around it; big targets for gym fingers. */
@Composable
fun Stepper(
    value: String,
    unit: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    minusLabel: String = "Меньше",
    plusLabel: String = "Больше",
    onLongValue: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(F.shape.radiusSmall + 2.dp)
    var m = modifier.clip(shape).background(F.c.surface2)
    if (F.shape.border > 0.dp) m = m.border(F.shape.border, F.c.line, shape)
    Row(m.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        StepButton(Ic.minus, minusLabel, onMinus)
        ValueBox(value, unit, Modifier.weight(1f), onLongValue)
        StepButton(Ic.plus, plusLabel, onPlus)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ValueBox(value: String, unit: String, modifier: Modifier, onLong: (() -> Unit)?) {
    var m = modifier
    if (onLong != null) m = m.combinedClickable(onClick = onLong, onLongClick = onLong)
    Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = T.number(26.sp), maxLines = 1)
        Text(labelText(unit), style = T.label())
    }
}

@Composable
private fun StepButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val shape = if (F.shape.pill) RoundedCornerShape(12.dp) else RoundedCornerShape(F.shape.radiusSmall)
    Box(
        Modifier.size(48.dp).clip(shape).background(if (F.c.dark) F.c.line else F.c.surface)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = F.c.text, modifier = Modifier.size(20.dp)) }
}

@Composable
fun TopBar(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (onBack != null) RoundIcon(Ic.back, "Назад", onBack, size = 42.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = T.body(17.sp, FontWeight.ExtraBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = T.secondary(13.sp), maxLines = 1)
        }
        actions()
    }
}

/** Segmented tabs: one selected option out of a few. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val outer = if (F.shape.pill) RoundedCornerShape(50) else RoundedCornerShape(F.shape.radiusSmall)
    var m = modifier.fillMaxWidth().clip(outer).background(F.c.surface)
    if (F.shape.border > 0.dp) m = m.border(F.shape.border, F.c.line, outer)
    Row(m.padding(4.dp)) {
        options.forEachIndexed { i, o ->
            val sel = i == selected
            Box(
                Modifier.weight(1f).height(38.dp).clip(outer).background(if (sel) F.c.accent else Color.Transparent)
                    .clickable(role = Role.Tab, onClick = { onSelect(i) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    labelText(o), style = T.body(13.sp, if (sel) FontWeight.ExtraBold else FontWeight.SemiBold)
                        .copy(color = if (sel) F.c.onAccent else F.c.text2), maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun Banner(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = Ic.info, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(F.shape.radiusSmall + 4.dp)
    var m = modifier.fillMaxWidth().clip(shape).background(color.copy(alpha = if (F.c.dark) 0.16f else 0.12f))
    if (onClick != null) m = m.clickable(onClick = onClick)
    Row(m.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, style = T.body(14.sp, FontWeight.SemiBold).copy(color = if (F.c.dark) color else F.c.text), modifier = Modifier.weight(1f))
        if (onClick != null) Icon(Ic.chevron, null, tint = color, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(if (F.shape.border > 1.dp) 1.5.dp else 1.dp).background(F.c.line))
}

/** One row of a list: title, optional subtitle, trailing text, chevron when clickable. */
@Composable
fun ListRow(title: String, subtitle: String? = null, trailing: String? = null, icon: ImageVector? = null, iconTint: Color = F.c.text2, onClick: (() -> Unit)? = null) {
    var m = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Row(m.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon != null) Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = T.body(16.sp, FontWeight.SemiBold))
            if (subtitle != null) Text(subtitle, style = T.secondary(13.sp))
        }
        if (trailing != null) T.number(16.sp, FontWeight.Bold).let { Text(numText(trailing, it), style = it) }
        if (onClick != null) Icon(Ic.chevron, null, tint = F.c.text3, modifier = Modifier.size(16.dp))
    }
}

/** Small coloured dot used for session kinds. */
@Composable
fun Dot(color: Color, size: Dp = 6.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** Text with numbers in the look's numbers face and units/letters in the text face. */
@Composable
fun NumText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    Text(numText(text, style), style = style, modifier = modifier, textAlign = textAlign, maxLines = maxLines, overflow = overflow)
}
