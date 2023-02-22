import junit.framework.TestCase;
import org.junit.Test;

public class SimpleFrequencyTableTest extends TestCase {
  @Test
  public void test() {
    SimpleFrequencyTable table = new SimpleFrequencyTable(new int[Math.negateExact(
            Byte.MIN_VALUE) + Byte.MAX_VALUE + 1]);
    char[] chars = "aaabbc".toCharArray();
    for (char c : chars)
      table.increment(c);
    table.initCumulative();
  }
}