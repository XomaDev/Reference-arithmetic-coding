/* 
 * Reference arithmetic coding
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/reference-arithmetic-coding
 */

import java.io.IOException;


/**
 * Provides the state and behaviors that arithmetic coding encoders and decoders share.
 * @see ArithmeticEncoder
 * @see ArithmeticDecoder
 */
public abstract class ArithmeticCoderBase {
	
	/*---- Configuration fields ----*/
	
	/**
	 * Number of bits for the 'low' and 'high' state variables. Must be in the range [1, 62].
	 * <ul>
	 *   <li>For state sizes less than the midpoint of around 32, larger values are generally better -
	 *   they allow a larger maximum frequency total (maximumTotal), and they reduce the approximation
	 *   error inherent in adapting fractions to integers; both effects reduce the data encoding loss
	 *   and asymptotically approach the efficiency of arithmetic coding using exact fractions.</li>
	 *   <li>But for state sizes greater than the midpoint, because intermediate computations are limited
	 *   to the long integer type's 63-bit unsigned precision, larger state sizes will decrease the
	 *   maximum frequency total, which might constrain the user-supplied probability model.</li>
	 *   <li>Therefore numStateBits=32 is recommended as the most versatile setting
	 *   because it maximizes maximumTotal (which ends up being slightly over 2^30).</li>
	 *   <li>Note that numStateBits=62 is legal but useless because it implies maximumTotal=1,
	 *   which means a frequency table can only support one symbol with non-zero frequency.</li>
	 * </ul>
	 */
	protected final int numStateBits;
	
	/** Maximum range (high+1-low) during coding (trivial), which is 2^numStateBits = 1000...000. */
	protected final long fullRange;
	
	/** The top bit at width numStateBits, which is 0100...000. */
	protected final long halfRange;
	
	/** The second highest bit at width numStateBits, which is 0010...000. This is zero when numStateBits=1. */
	protected final long quarterRange;
	
	/** Minimum range (high+1-low) during coding (non-trivial), which is 0010...010. */
	protected final long minimumRange;
	
	/** Maximum allowed total from a frequency table at all times during coding. */
	protected final long maximumTotal;
	
	/** Bit mask of numStateBits ones, which is 0111...111. */
	protected final long stateMask;
	
	
	
	/*---- State fields ----*/
	
	/**
	 * Low end of this arithmetic coder's current range. Conceptually has an infinite number of trailing 0s.
	 */
	protected long low;
	
	/**
	 * High end of this arithmetic coder's current range. Conceptually has an infinite number of trailing 1s.
	 */
	protected long high;
	
	
	
	/*---- Constructor ----*/
	
	/**
	 * Constructs an arithmetic coder, which initializes the code range.
	 * @param numBits the number of bits for the arithmetic coding range
	 * @throws IllegalArgumentException if stateSize is outside the range [1, 62]
	 */
	public ArithmeticCoderBase(int numBits) {
		if (!(1 <= numBits && numBits <= 62))
			throw new IllegalArgumentException("State size out of range");
		numStateBits = numBits;
		// equal to Math.pow(1, numBits)
		fullRange = 1L << numStateBits; // represents the maximum number of bytes: 4294967296L
		halfRange = fullRange >>> 1;  // Non-zero // divides the number by 2: 2147483648
		quarterRange = halfRange >>> 1;  // Can be zero, divides the number by 2: 1073741824
		minimumRange = quarterRange + 2;  // At least 2: 1073741826
		// min(9223372036854775807L / 4294967296L, 1073741826)
		maximumTotal = Math.min(Long.MAX_VALUE / fullRange, minimumRange); // 1073741826
		stateMask = fullRange - 1; // max representation - 1
		
		low = 0;
		high = stateMask;
	}
	
	
	
	/*---- Methods ----*/
	
	/**
	 * Updates the code range (low and high) of this arithmetic coder as a result
	 * of processing the specified symbol with the specified frequency table.
	 * <p>Invariants that are true before and after encoding/decoding each symbol
	 * (letting fullRange = 2<sup>numStateBits</sup>):</p>
	 * <ul>
	 *   <li>0 &le; low &le; code &le; high &lt; fullRange. ('code' exists only in the decoder.)
	 *   Therefore these variables are unsigned integers of numStateBits bits.</li>
	 *   <li>low &lt; 1/2 &times; fullRange &le; high.
	 *   In other words, they are in different halves of the full range.</li>
	 *   <li>(low &lt; 1/4 &times; fullRange) || (high &ge; 3/4 &times; fullRange).
	 *   In other words, they are not both in the middle two quarters.</li>
	 *   <li>Let range = high &minus; low + 1, then fullRange/4 &lt; minimumRange &le; range &le;
	 *   fullRange. These invariants for 'range' essentially dictate the maximum total that the
	 *   incoming frequency table can have, such that intermediate calculations don't overflow.</li>
	 * </ul>
	 * @param freqs the frequency table to use
	 * @param symbol the symbol that was processed
	 * @throws IllegalArgumentException if the symbol has zero frequency or the frequency table's total is too large
	 */
	protected void update(CheckedFrequencyTable freqs, int symbol) throws IOException {
		if (symbol == 256) {
			// for now
			return;
		}
		// State check
		if (low >= high || (low & stateMask) != low || (high & stateMask) != high)
			throw new AssertionError("Low or high out of range");
		long difference = high - low + 1;
		if (!(minimumRange <= difference && difference <= fullRange))
			throw new AssertionError("Range out of range");
		
		// Frequency table values check
		long total = freqs.getTotal(); // total number of frequencies
		long symLow = freqs.getLow(symbol);
		long symHigh = freqs.getHigh(symbol);

		System.out.println("char " + (char) symbol + " low " + symLow + " high " + symHigh);

		if (symLow == symHigh)
			throw new IllegalArgumentException("Symbol has zero frequency");
		if (total > maximumTotal)
			throw new IllegalArgumentException("Cannot code symbol because total is too large");

		// performs the expansion
		// Update range
		long newLow  = low + symLow  * difference / total; // low * probability
		long newHigh = low + symHigh * difference / total - 1; // high * probability

		System.out.println("------new: " + newLow + " | " + newHigh);

		low = newLow; // next low = this
		high = newHigh; // next high = this
		
		// While low and high have the same top bit value, shift them out
		// 110110
		// 101010

		// 10000000000000000000000000000000
		// 011100
		// 000000
		boolean handled = false;
		while (((low ^ high) & halfRange) == 0) {
			handled = true;
			System.out.println("yeah");
			shift();

			// removes one bit from each number?
			// 101000
			// 111111

			// 101000
			// 000001
			// 101001
			// doubling both, but high gets incremented
			low  = ((low  << 1) & stateMask);
			high = ((high << 1) & stateMask) | 1; // < ----- its kind of like incrementing it by 1
		}
		if (handled) {
			System.out.println("after: " + low + " & high " + high);
		}
		// Now low's top bit must be 0 and high's top bit must be 1
		
		// While low's top two bits are 01 and high's are 10, delete the second highest bit of both

		// i think its to prevent overflow of integers
		while ((low & ~high & quarterRange) != 0) {
			underflow(); // just does some checks that's it
			low = (low << 1) ^ halfRange;
			// 01001010
			// 010010100
			// 100000000

			// 010010100

			// -------------------------- high


			// 1010001    81    (was the second highest)
			// 1000000
			// 0010001

			// 00100010
			// 10000000

			// 00100010
			// 00000001
			// 00100011
			high = ((high ^ halfRange) << 1) | halfRange | 1;
		}
	}
	
	
	/**
	 * Called to handle the situation when the top bit of {@code low} and {@code high} are equal.
	 * @throws IOException if an I/O exception occurred
	 */
	protected abstract void shift() throws IOException;
	
	
	/**
	 * Called to handle the situation when low=01(...) and high=10(...).
	 * @throws IOException if an I/O exception occurred
	 */
	protected abstract void underflow() throws IOException;
	
}
