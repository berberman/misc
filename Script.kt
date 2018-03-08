import javax.script.ScriptEngineManager
  
fun Array<String>.main() {
  val engine = ScriptEngineManager().getEngineByName("nashorn")
  engine.eval("println(233)")
}
