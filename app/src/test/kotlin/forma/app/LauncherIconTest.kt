package forma.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertTrue

/** The launcher icon's vectors draw (a bad path would leave the home screen blank), and how they look: build/shots. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class LauncherIconTest {
    @Test fun theIconDraws() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        for (name in listOf("ic_launcher_foreground", "ic_launcher_monochrome")) {
            val id = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
            val d = ContextCompat.getDrawable(ctx, id)!!
            val bmp = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
            Canvas(bmp).apply { drawColor(0xFF0D0D0D.toInt()); d.setBounds(0, 0, 432, 432); d.draw(this) }
            // Something light in the middle third (the plate and the hub), nothing outside the safe circle.
            var lit = 0
            for (x in 144 until 288) for (y in 144 until 288) if (bmp.getPixel(x, y) and 0xFF > 0x80) lit++
            assertTrue(lit > 3000, "$name draws in the middle: $lit")
            assertTrue(bmp.getPixel(10, 10) and 0xFFFFFF == 0x0D0D0D, "$name leaves the corners empty")
            File("build/shots").mkdirs()
            File("build/shots/launcher-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
