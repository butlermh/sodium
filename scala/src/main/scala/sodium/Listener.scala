package sodium

class Listener {

  def unlisten() {}

  /**
   * Combine listeners into one where a single unlisten() invocation will unlisten
   * both the inputs.
   */
  final def append(two: Listener): Listener = {
    val one = this
    new Listener() {
      def unlisten() {
        one.unlisten()
        two.unlisten()
      }
    }
  }
}
