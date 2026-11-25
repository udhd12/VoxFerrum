package dev.voxferrum;

/** Converts one fixed-duration mono block to the archive's frame count, retaining the boundary sample. */
final class MonoBlockConverter {
    private short previous;
    private boolean hasPrevious;

    void convert(short[] input, short[] output) {
        if (input == output) return;
        // The first source sample in this block falls one source period after the previous one.
        int denominator = output.length;
        short prior = hasPrevious ? previous : input[0];
        for (int frame = 0; frame < output.length; frame++) {
            int position = (frame + 1) * input.length;
            int whole = position / denominator;
            int remainder = position % denominator;
            int left = whole == 0 ? prior : input[whole - 1];
            int right = whole == input.length ? left : input[whole];
            output[frame] = (short)(left + (right - left) * remainder / denominator);
        }
        previous = input[input.length - 1];
        hasPrevious = true;
    }
}
