package ar.com.legajos.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteConstraintException
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private data class Record(val id: Long, val number: String, val first: String, val last: String, val dni: String, val date: String, val location: String, val otherFirst: String, val otherLast: String)

private class RecordsDb(ctx: Context) : SQLiteOpenHelper(ctx, "legajos.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records (id INTEGER PRIMARY KEY AUTOINCREMENT, number TEXT NOT NULL UNIQUE, first TEXT NOT NULL, last TEXT NOT NULL, dni TEXT NOT NULL UNIQUE, date TEXT, location TEXT, other_first TEXT NOT NULL DEFAULT '', other_last TEXT NOT NULL DEFAULT '')")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE records ADD COLUMN other_first TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE records ADD COLUMN other_last TEXT NOT NULL DEFAULT ''")
        }
    }
    fun all(query: String = ""): List<Record> {
        val out = mutableListOf<Record>()
        val q = "%${query.trim()}%"
        readableDatabase.rawQuery("SELECT id,number,first,last,dni,date,location,other_first,other_last FROM records WHERE number LIKE ? OR first LIKE ? OR last LIKE ? OR dni LIKE ? OR other_first LIKE ? OR other_last LIKE ? ORDER BY last,first", arrayOf(q,q,q,q,q,q)).use { c ->
            while (c.moveToNext()) out.add(Record(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5) ?: "",c.getString(6) ?: "",c.getString(7) ?: "",c.getString(8) ?: ""))
        }
        return out
    }
    fun save(r: Record): Boolean {
        val cv = ContentValues().apply {
            put("number",r.number); put("first",r.first); put("last",r.last); put("dni",r.dni)
            put("date",r.date); put("location",r.location); put("other_first",r.otherFirst); put("other_last",r.otherLast)
        }
        return try {
            if (r.id == 0L) writableDatabase.insertOrThrow("records",null,cv) > 0
            else writableDatabase.update("records",cv,"id=?",arrayOf(r.id.toString())) == 1
        } catch (_: SQLiteConstraintException) { false }
    }
    fun nextNumber(): String {
        val used = all().mapNotNull { it.number.toLongOrNull() }.toSet()
        var n = 1L
        while (n in used) n++
        return n.toString().padStart(5,'0')
    }
    fun json(): String = JSONArray().apply {
        all().forEach { r -> put(JSONObject().put("number",r.number).put("first",r.first).put("last",r.last).put("dni",r.dni).put("date",r.date).put("location",r.location).put("other_first",r.otherFirst).put("other_last",r.otherLast)) }
    }.toString()
    fun replaceFromJson(raw: String): Int {
        val arr = JSONArray(raw)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("records",null,null)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val cv = ContentValues().apply {
                    put("number",o.getString("number")); put("first",o.getString("first")); put("last",o.getString("last")); put("dni",o.getString("dni"))
                    put("date",o.optString("date")); put("location",o.optString("location")); put("other_first",o.optString("other_first")); put("other_last",o.optString("other_last"))
                }
                db.insertOrThrow("records",null,cv)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return arr.length()
    }
}

class MainActivity : Activity() {
    private lateinit var db: RecordsDb
    private lateinit var root: LinearLayout
    private lateinit var search: EditText
    private val prefs by lazy { getSharedPreferences("access",MODE_PRIVATE) }
    private val createBackup = 41
    private val openBackup = 42
    private var pendingPassword = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = RecordsDb(this)
        showPin()
    }
    private fun sha(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun pinInput(hintText: String) = field(hintText,InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    private fun showPin() {
        val stored = prefs.getString("pinHash",null)
        if (stored == null) { setupPin(); return }
        val input = pinInput("PIN de 6 dígitos")
        val container = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),0,dp(20),0); addView(input) }
        val dlg = AlertDialog.Builder(this).setTitle("Ingresar PIN").setView(container).setCancelable(false)
            .setPositiveButton("Ingresar",null).setNeutralButton("Recuperar acceso",null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString()
                if (value.length != 6 || !value.all(Char::isDigit)) { input.error="Ingresá 6 números"; return@setOnClickListener }
                if (sha(value) == stored) { dlg.dismiss(); showHome() }
                else input.error="PIN incorrecto"
            }
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (prefs.getString("recoveryHash",null) == null) {
                    toast("Esta instalación aún no tiene clave de recuperación. Necesitás el PIN original para activarla.")
                } else { dlg.dismiss(); recoverPin() }
            }
        }
        dlg.show()
    }
    private fun setupPin() {
        val pin = pinInput("Crear PIN de 6 números")
        val confirm = pinInput("Repetir PIN")
        val recovery = field("Clave de recuperación (mínimo 12 caracteres)")
        val layout = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),0,dp(20),0); addView(pin); addView(confirm); addView(recovery) }
        val dlg = AlertDialog.Builder(this).setTitle("Configurar acceso").setMessage("Elegí un PIN y una clave de recuperación distinta. Guardá esa clave fuera del teléfono: si la olvidás, no podremos restablecer el acceso.")
            .setView(layout).setCancelable(false).setPositiveButton("Guardar",null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p = pin.text.toString(); val c = confirm.text.toString(); val r = recovery.text.toString()
                when {
                    p.length != 6 || !p.all(Char::isDigit) -> pin.error="Usá exactamente 6 números"
                    p != c -> confirm.error="Los PIN no coinciden"
                    r.length < 12 || r == p -> recovery.error="Usá una clave distinta de al menos 12 caracteres"
                    else -> { prefs.edit().putString("pinHash",sha(p)).putString("recoveryHash",sha(r)).apply(); dlg.dismiss(); showHome() }
                }
            }
        }
        dlg.show()
    }
    private fun recoverPin() {
        val recovery = field("Clave de recuperación")
        val pin = pinInput("Nuevo PIN de 6 números")
        val confirm = pinInput("Repetir nuevo PIN")
        val layout = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),0,dp(20),0); addView(recovery); addView(pin); addView(confirm) }
        val dlg = AlertDialog.Builder(this).setTitle("Recuperar acceso").setView(layout).setCancelable(false)
            .setNegativeButton("Volver") { _,_ -> showPin() }.setPositiveButton("Cambiar PIN",null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p=pin.text.toString()
                when {
                    sha(recovery.text.toString()) != prefs.getString("recoveryHash",null) -> recovery.error="Clave incorrecta"
                    p.length != 6 || !p.all(Char::isDigit) -> pin.error="Usá 6 números"
                    p != confirm.text.toString() -> confirm.error="Los PIN no coinciden"
                    else -> { prefs.edit().putString("pinHash",sha(p)).apply(); dlg.dismiss(); showHome() }
                }
            }
        }
        dlg.show()
    }
    private fun activateRecovery() {
        val old = prefs.getString("recoveryHash",null)
        val a=field(if (old == null) "Crear clave de recuperación" else "Nueva clave de recuperación")
        val b=field("Repetir clave de recuperación")
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),0,dp(20),0); addView(a); addView(b) }
        val dlg=AlertDialog.Builder(this).setTitle("Clave de recuperación").setMessage("Guardá esta clave en un lugar seguro. No se podrá consultar después.").setView(layout).setNegativeButton("Cancelar",null).setPositiveButton("Guardar",null).create()
        dlg.setOnShowListener { dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val v=a.text.toString()
            when { v.length < 12 -> a.error="Mínimo 12 caracteres"; v != b.text.toString() -> b.error="No coinciden"
                else -> { prefs.edit().putString("recoveryHash",sha(v)).apply(); dlg.dismiss(); toast("Clave de recuperación configurada") }
            }
        } }
        dlg.show()
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(s: String, size: Float = 16f) = TextView(this).apply { text=s; textSize=size; setTextColor(0xff183d34.toInt()); setPadding(0,dp(7),0,dp(7)) }
    private fun button(s: String, action: () -> Unit) = Button(this).apply { text=s; setOnClickListener { action() } }
    private fun field(hintText: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply { hint=hintText; inputType=type; setSingleLine(true) }
    private fun base(title: String) {
        root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(16),dp(18),dp(18)); setBackgroundColor(0xfff5f8f6.toInt()) }
        setContentView(ScrollView(this).apply { addView(root) })
        root.addView(text(title,25f)); root.addView(text("Registro local · Sin conexión a internet",13f))
    }
    private fun showHome() {
        base("Legajos de Pacientes")
        search = field("Buscar paciente, DNI, legajo u otra parte")
        root.addView(search)
        root.addView(button("Buscar") { renderResults() })
        root.addView(button("+ Registrar legajo") { showEditor(null) })
        root.addView(button("Configurar clave de recuperación") { activateRecovery() })
        root.addView(button("Crear copia cifrada") { askBackupPassword(true) })
        root.addView(button("Restaurar copia cifrada") { askBackupPassword(false) })
        root.addView(text("La restauración reemplaza todos los registros actuales. Guardá las copias en un lugar seguro.",12f))
        renderResults()
    }
    private fun renderResults() {
        root.findViewWithTag<View>("results")?.let { root.removeView(it) }
        val box = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; tag="results" }
        val rows = db.all(if (::search.isInitialized) search.text.toString() else "")
        box.addView(text("Resultados: ${rows.size}",18f))
        rows.forEach { r ->
            val item=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(8),dp(12),dp(8)); setBackgroundColor(0xffffffff.toInt()) }
            item.addView(text("${r.last}, ${r.first}",18f))
            item.addView(text("DNI: ${r.dni} · Legajo: ${r.number}",14f))
            item.addView(text("Otra parte: ${listOf(r.otherFirst,r.otherLast).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Sin registrar" }}",14f))
            item.addView(text("Ubicación: ${r.location.ifBlank { "Sin ubicación registrada" }}",14f))
            item.addView(text("Creación del legajo: ${r.date.ifBlank { "No especificada" }}",12f))
            item.addView(button("Editar legajo") { showEditor(r) })
            box.addView(item)
            box.addView(View(this),LinearLayout.LayoutParams(1,dp(8)))
        }
        root.addView(box)
    }
    private fun showEditor(existing: Record?) {
        base(if (existing == null) "Nuevo legajo" else "Modificar legajo")
        fun input(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText = field(hint,type).also { it.setText(value); root.addView(it) }
        val number=input("Número de legajo",existing?.number ?: db.nextNumber())
        val first=input("Nombre",existing?.first ?: "")
        val last=input("Apellido",existing?.last ?: "")
        val dni=input("DNI",existing?.dni ?: "",InputType.TYPE_CLASS_NUMBER)
        val date=input("Fecha de creación del legajo (DD/MM/AAAA)",existing?.date ?: "")
        val otherFirst=input("Nombre de otra parte",existing?.otherFirst ?: "")
        val otherLast=input("Apellido de otra parte",existing?.otherLast ?: "")
        val location=input("Ubicación del legajo",existing?.location ?: "")
        root.addView(button("Guardar cambios") {
            val n=number.text.toString().trim(); val f=first.text.toString().trim(); val l=last.text.toString().trim(); val d=dni.text.toString().trim()
            if (n.isBlank() || f.isBlank() || l.isBlank() || d.isBlank()) { toast("Completá número, nombre, apellido y DNI"); return@button }
            if (!d.all(Char::isDigit)) { dni.error="Ingresá solo números"; return@button }
            val dt=date.text.toString().trim()
            if (dt.isNotBlank() && !Regex("\\d{2}/\\d{2}/\\d{4}").matches(dt)) { date.error="Formato DD/MM/AAAA"; return@button }
            val record=Record(existing?.id ?: 0L,n,f,l,d,dt,location.text.toString().trim(),otherFirst.text.toString().trim(),otherLast.text.toString().trim())
            if (db.save(record)) { toast("Legajo guardado"); showHome() }
            else toast("No se pudo guardar: el DNI o número de legajo ya existe")
        })
        root.addView(button("Volver") { showHome() })
    }
    private fun askBackupPassword(export: Boolean) {
        val input=field("Contraseña de la copia (mínimo 8 caracteres)",InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val dlg=AlertDialog.Builder(this).setTitle(if (export) "Cifrar copia de seguridad" else "Abrir copia de seguridad")
            .setMessage("Guardá la contraseña de la copia. Sin ella no se puede restaurar.").setView(input).setNegativeButton("Cancelar",null).setPositiveButton("Continuar",null).create()
        dlg.setOnShowListener { dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val pass=input.text.toString()
            if (pass.length < 8) { input.error="Mínimo 8 caracteres"; return@setOnClickListener }
            pendingPassword=pass
            dlg.dismiss()
            if (export) {
                val i=android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(android.content.Intent.EXTRA_TITLE,"legajos-copia.enc")
                startActivityForResult(i,createBackup)
            } else {
                val i=android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("application/octet-stream")
                startActivityForResult(i,openBackup)
            }
        } }
        dlg.show()
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (resultCode != RESULT_OK || data?.data == null) { pendingPassword=""; return }
        val pass=pendingPassword; pendingPassword=""
        try {
            val uri=data.data!!
            if (requestCode == createBackup) {
                val encrypted=encrypt(db.json().toByteArray(Charsets.UTF_8),pass)
                contentResolver.openOutputStream(uri)?.use { it.write(encrypted) } ?: throw Exception("No se pudo guardar")
                toast("Copia cifrada creada")
            } else if (requestCode == openBackup) {
                val bytes=contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Exception("No se pudo leer")
                val raw=decrypt(bytes,pass).toString(Charsets.UTF_8)
                JSONArray(raw) // Comprobar formato antes de ofrecer reemplazo
                AlertDialog.Builder(this).setTitle("Confirmar restauración").setMessage("Se reemplazarán todos los registros. ¿Continuar?")
                    .setNegativeButton("Cancelar",null).setPositiveButton("Restaurar") { _,_ ->
                        try { val n=db.replaceFromJson(raw); toast("Registros restaurados: $n"); showHome() }
                        catch (_: Exception) { toast("No se pudo restaurar la copia") }
                    }.show()
            }
        } catch (_: Exception) { toast("No se pudo procesar la copia. Revisá el archivo y la contraseña.") }
    }
    private fun encrypt(plain: ByteArray, password: String): ByteArray {
        val salt=ByteArray(16); val iv=ByteArray(12); SecureRandom().nextBytes(salt); SecureRandom().nextBytes(iv)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,derive(password,salt),GCMParameterSpec(128,iv))
        return "LGJ1".toByteArray() + salt + iv + cipher.doFinal(plain)
    }
    private fun decrypt(all: ByteArray, password: String): ByteArray {
        if (all.size < 48 || String(all.copyOfRange(0,4)) != "LGJ1") throw Exception("Formato incorrecto")
        val salt=all.copyOfRange(4,20); val iv=all.copyOfRange(20,32)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,derive(password,salt),GCMParameterSpec(128,iv))
        return cipher.doFinal(all.copyOfRange(32,all.size))
    }
    private fun derive(password: String, salt: ByteArray): SecretKeySpec {
        val spec=PBEKeySpec(password.toCharArray(),salt,210000,256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,"AES")
    }
    private fun toast(s: String) = Toast.makeText(this,s,Toast.LENGTH_LONG).show()
}
