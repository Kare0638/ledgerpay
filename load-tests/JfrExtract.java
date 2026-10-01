import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/**
 * Prints the events analyze_jfr.py needs as one tab-separated line each: type, duration in ms,
 * class (monitor, parked, allocated or GC name), thread, weight or pause, then stack frames. Far
 * smaller and faster than {@code jfr print --json} with deep stacks.
 *
 * <p>Usage: {@code java load-tests/JfrExtract.java recording.jfr}
 */
public class JfrExtract {

  private static final Set<String> TYPES =
      Set.of(
          "jdk.GarbageCollection",
          "jdk.JavaMonitorEnter",
          "jdk.ThreadPark",
          "jdk.VirtualThreadPinned",
          "jdk.ExecutionSample",
          "jdk.ObjectAllocationSample");

  public static void main(String[] args) throws IOException {
    try (var recording = new RecordingFile(Path.of(args[0]))) {
      var out = new StringBuilder();
      while (recording.hasMoreEvents()) {
        RecordedEvent event = recording.readEvent();
        String type = event.getEventType().getName();
        if (!TYPES.contains(type)) {
          continue;
        }
        out.setLength(0);
        out.append(type).append('\t').append(event.getDuration().toNanos() / 1e6).append('\t');
        out.append(subject(event, type)).append('\t');
        RecordedThread thread = event.getThread();
        out.append(thread == null ? "?" : thread.getJavaName()).append('\t');
        out.append(extra(event, type));
        // Allocation and CPU samples only need their top frame; waits need the caller.
        int depth = type.equals("jdk.ObjectAllocationSample") ? 0 : type.equals("jdk.ExecutionSample") ? 1 : 64;
        if (event.getStackTrace() != null && depth > 0) {
          List<RecordedFrame> frames = event.getStackTrace().getFrames();
          out.append('\t')
              .append(
                  frames.stream()
                      .limit(depth)
                      .map(f -> f.getMethod().getType().getName() + "." + f.getMethod().getName())
                      .collect(Collectors.joining("\t")));
        }
        System.out.println(out);
      }
    }
  }

  private static String subject(RecordedEvent event, String type) {
    return switch (type) {
      case "jdk.JavaMonitorEnter" -> name(event.getClass("monitorClass"));
      case "jdk.ThreadPark" -> name(event.getClass("parkedClass"));
      case "jdk.ObjectAllocationSample" -> name(event.getClass("objectClass"));
      case "jdk.GarbageCollection" -> event.getString("name");
      default -> "-";
    };
  }

  private static String extra(RecordedEvent event, String type) {
    return switch (type) {
      case "jdk.ObjectAllocationSample" -> String.valueOf(event.getLong("weight"));
      case "jdk.GarbageCollection" ->
          event.getDuration("sumOfPauses").toNanos() / 1e6
              + "/"
              + event.getDuration("longestPause").toNanos() / 1e6;
      default -> "-";
    };
  }

  private static String name(RecordedClass type) {
    return type == null ? "-" : type.getName();
  }
}
