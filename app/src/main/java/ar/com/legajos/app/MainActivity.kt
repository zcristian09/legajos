package ar.com.legajos.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private data class Record(val id: Long, val number: String, val first: String, val last: String, val dni: String, val date: String, val location: String)

private class RecordsDb(ctx: Context) : SQLiteOpenHelper(ctx, "legajos.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records (id INTEGER PRIMARY KEY AUTOINCREMENT, number TEXT NOT NULL UNIQUE, first TEXT NOT NULL, last TEXT NOT NULL, dni TEXT NOT NULL UNIQUE, date TEXT, location TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun all(query: String = ""): List<Record> {
        val out = mutableListOf<Record>()
        val c = readableDatabase.rawQuery("SELECT id,number,first,last,dni,date,location FROM records WHERE number LIKE ? OR first LIKE ? OR last LIKE ? OR dni LIKE ? ORDER BY last,first", arrayOf("%$query%","%$query%","%$query%","%$query%"))
        c.use { while (it.moveToNext()) out.add(Record(it.getLong(0),it.getString(1),it.getString(2),it.getString(3),it.getString(4),it.getString(5) ?: "",it.getString(6) ?: "")) }
        return out
    }
    fun insert(first: String, last: String, dni: String, date: String, location: String): Boolean {
        if (readableDatabase.rawQuery("SELECT id FROM records WHERE dni=?", arrayOf(dni)).use { it.count > 0 }) return false
        val next = (readableDatabase.rawQuery("SELECT COUNT(*) FROM records", null).use { if (it.moveToFirst()) it.getInt(0) + 1 else 1 }).toString().padStart(5,'0')
        val cv = ContentValues().apply { put("number", next); put("first",first); put("last",last); put("dni",dni); put("date",date); put("location",location) }
        return writableDatabase.insert("records", null, cv) != -1L
    }
    fun json(): String {
        val arr = JSONArray()
        all().forEach { r -> arr.put(JSONObject().put("number",r.number).put("first",r.first).put("last",r.last).put("dni",r.dni).put("date",r.date).put("location",r.location)) }
        return arr.toString()
    }
    fun replaceFromJson(raw: String): Int {
        val arr = JSONArray(raw)
        val db = writableDatabase
        db.beginTransaction()
        var count = 0
        try {
            db.delete("records", null, null)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val cv = ContentValues().apply { put("number",o.optString("number")); put("first",o.optString("first")); put("last",o.optString("last")); put("dni",o.optString("dni")); put("date",o.optString("date")); put("location",o.optString("location")) }
                if (db.insertOrThrow("records", null, cv) != -1L) count++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return count
    }
}

class MainActivity : Activity() {
    private lateinit var db: RecordsDb
    private lateinit var root: LinearLayout
    private lateinit var search: EditText
    private val prefs by lazy { getSharedPreferences("access", MODE_PRIVATE) }
    private val createBackup = 41
    private val openBackup = 42
    private var pendingPassword = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = RecordsDb(this)
        if (!checkPin()) return
        showHome()
    }
    private fun checkPin(): Boolean {
        val stored = prefs.getString("pinHash", null)
        val input = EditText(this).apply { hint = if (stored == null) "Crear PIN de 6 o más caracteres" else "Ingresar PIN"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val dlg = AlertDialog.Builder(this).setTitle("Legajos de Pacientes").setMessage("Acceso local al registro").setView(input)
            .setCancelable(false).setPositiveButton(if (stored == null) "Crear PIN" else "Ingresar", null).create()
        dlg.setOnShowListener {
            dlg.getButton(-1).setOnClickListener {
                val value = input.text.toString()
                if (value.length < 6) { input.error = "Usá al menos 6 caracteres"; return@setOnClickListener }
                val hash = sha(value)
                if (stored == null) { prefs.edit().putString("pinHash", hash).apply(); dlg.dismiss(); showHome() }
                else if (hash == stored) { dlg.dismiss(); showHome() }
                else input.error = "PIN incorrecto"
            }
        }
        dlg.show()
        return false
    }
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(s: String, size: Float = 16f) = TextView(this).apply { text=s; textSize=size; setTextColor(0xff183d34.toInt()); setPadding(0,dp(7),0,dp(7)) }
    private fun button(s: String, action: () -> Unit) = Button(this).apply { text=s; setOnClickListener { action() } }
    private fun field(hintText: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply { hint=hintText; inputType=type; setSingleLine(true) }
    private fun base(title: String) {
        root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(16),dp(18),dp(18)); setBackgroundColor(0xfff5f8f6.toInt()) }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        root.addView(text(title, 25f))
        root.addView(text("Registro local · Sin conexión a internet", 13f))
    }
    private fun showHome() {
        base("Legajos de Pacientes")
        search = field("Buscar por DNI, nombre, apellido o número")
        root.addView(search)
        root.addView(button("Buscar") { renderResults() })
        root.addView(button("+ Registrar legajo") { showAdd() })
        root.addView(button("Crear copia cifrada") { askBackupPassword(true) })
        root.addView(button("Restaurar copia cifrada") { askBackupPassword(false) })
        root.addView(text("Guardá las copias en un lugar seguro. La restauración reemplaza el registro actual.", 12f))
        renderResults()
    }
    private fun renderResults() {
        val old = root.findViewWithTag<View>("results")
        if (old != null) root.removeView(old)
        val box = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; tag="results" }
        val q = if (::search.isInitialized) search.text.toString().trim() else ""
        val rows = db.all(q)
        box.addView(text("Resultados: ${rows.size}", 18f))
        rows.forEach { r ->
            val item = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(8),dp(12),dp(8)); setBackgroundColor(0xffffffff.toInt()) }
            item.addView(text("${r.last}, ${r.first}",18f))
            item.addView(text("DNI: ${r.dni} · Legajo: ${r.number}",14f))
            item.addView(text("Ubicación: ${r.location.ifBlank { "Sin ubicación registrada" }}",14f))
            item.addView(text("Fecha: ${r.date.ifBlank { "No especificada" }}",12f))
            box.addView(item)
            val gap = View(this); box.addView(gap, LinearLayout.LayoutParams(1,dp(8)))
        }
        root.addView(box)
    }
    private fun showAdd() {
        base("Nuevo legajo")
        val first=field("Nombre"); val last=field("Apellido"); val dni=field("DNI",InputType.TYPE_CLASS_NUMBER); val date=field("Fecha de apertura (opcional)"); val location=field("Ubicación: armario, estante, carpeta")
        listOf(first,last,dni,date,location).forEach { root.addView(it) }
        root.addView(button("Guardar") {
            if (first.text.isBlank() || last.text.isBlank() || dni.text.isBlank()) { toast("Completá nombre, apellido y DNI"); return@button }
            if (db.insert(first.text.toString().trim(),last.text.toString().trim(),dni.text.toString().trim(),date.text.toString().trim(),location.text.toString().trim())) { toast("Legajo guardado"); showHome() }
            else toast("Ese DNI ya existe. Buscalo antes de crear otro legajo.")
        })
        root.addView(button("Volver") { showHome() })
    }
    private fun askBackupPassword(export: Boolean) {
        val input = EditText(this).apply { hint="Contraseña de la copia (mínimo 8 caracteres)"; inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(this).setTitle(if (export) "Cifrar copia de seguridad" else "Abrir copia de seguridad").setMessage("Usá una contraseña fuerte y guardala en un lugar seguro. Si la olvidás, no se podrá recuperar la copia.").setView(input)
            .setNegativeButton("Cancelar",null).setPositiveButton("Continuar") { _,_ ->
                val pass=input.text.toString()
                if (pass.length < 8) { toast("La contraseña debe tener al menos 8 caracteres"); return@setPositiveButton }
                pendingPassword=pass
                if (export) { val i=android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(android.content.Intent.EXTRA_TITLE,"legajos-copia.enc"); startActivityForResult(i,createBackup) }
                else { val i=android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("application/octet-stream"); startActivityForResult(i,openBackup) }
            }.show()
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (resultCode != RESULT_OK || data?.data == null) return
        try {
            val uri=data.data!!
            if (requestCode == createBackup) {
                val encrypted=encrypt(db.json().toByteArray(Charsets.UTF_8),pendingPassword)
                contentResolver.openOutputStream(uri)?.use { it.write(encrypted) } ?: throw Exception("No se pudo guardar el archivo")
                toast("Copia cifrada creada")
            } else if (requestCode == openBackup) {
                val bytes=contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Exception("No se pudo leer el archivo")
                val raw=decrypt(bytes,pendingPassword).toString(Charsets.UTF_8)
                AlertDialog.Builder(this).setTitle("Confirmar restauración").setMessage("Se reemplazarán todos los registros actuales. ¿Querés continuar?").setNegativeButton("Cancelar",null).setPositiveButton("Restaurar") { _,_ -> try { val n=db.replaceFromJson(raw); toast("Registros restaurados: $n"); showHome() } catch (e:Exception) { toast("La copia no es válida") } }.show()
            }
        } catch (e: Exception) { toast("No se pudo procesar la copia. Revisá la contraseña y el archivo.") }
    }
    private fun encrypt(plain: ByteArray, password: String): ByteArray {
        val salt=ByteArray(16); val iv=ByteArray(12); SecureRandom().nextBytes(salt); SecureRandom().nextBytes(iv)
        val key=derive(password,salt); val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key,GCMParameterSpec(128,iv))
        val encrypted=cipher.doFinal(plain)
        return "LGJ1".toByteArray() + salt + iv + encrypted
    }
    private fun decrypt(all: ByteArray, password: String): ByteArray {
        if (all.size < 48 || String(all.copyOfRange(0,4)) != "LGJ1") throw Exception("Formato incorrecto")
        val salt=all.copyOfRange(4,20); val iv=all.copyOfRange(20,32); val encrypted=all.copyOfRange(32,all.size)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,derive(password,salt),GCMParameterSpec(128,iv)); return cipher.doFinal(encrypted)
    }
    private fun derive(password: String, salt: ByteArray): SecretKeySpec {
        val spec=PBEKeySpec(password.toCharArray(),salt,210000,256)
        val bytes=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes,"AES")
    }
    private fun toast(s:String) = Toast.makeText(this,s,Toast.LENGTH_LONG).show()
}
