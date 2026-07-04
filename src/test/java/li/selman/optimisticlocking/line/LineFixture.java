package li.selman.optimisticlocking.line;

import java.time.Instant;
import java.util.UUID;
import org.springframework.test.util.ReflectionTestUtils;

public class LineFixture {

    public static final LineId CONSTANT_ID = new LineId(UUID.fromString("019f1ef9-f540-7105-9509-aeb9c2753a8b"));

    public static LineBuilder newBuilder() {
        return new LineBuilder();
    }

    public static class LineBuilder {

        private UUID id = LineFixture.CONSTANT_ID.id();
        private int left = 1;
        private int right = 5;
        private long lockVersion = 1;
        private Instant updatedAt;
        private int leftUpdates = 0;
        private int rightUpdates = 0;

        public LineBuilder id(UUID id) {
            this.id = id;
            return this;
        }

        public LineBuilder randomId() {
            return id(UUID.randomUUID());
        }

        public LineBuilder left(int left) {
            this.left = left;
            return this;
        }

        public LineBuilder right(int right) {
            this.right = right;
            return this;
        }

        public LineBuilder line(int left, int right) {
            this.left = left;
            this.right = right;
            return this;
        }

        public LineBuilder lockVersion(long lockVersion) {
            this.lockVersion = lockVersion;
            return this;
        }

        public LineBuilder updatedNow() {
            this.updatedAt = Instant.now();
            return this;
        }

        public LineBuilder leftUpdates(int leftUpdates) {
            this.leftUpdates = leftUpdates;
            return this;
        }

        public LineBuilder rightUpdates(int rightUpdates) {
            this.rightUpdates = rightUpdates;
            return this;
        }

        public Line build() {
            Line line = new Line(new LineId(id), left, right);
            ReflectionTestUtils.setField(line, "lockVersion", lockVersion);
            ReflectionTestUtils.setField(line, "updatedAt", updatedAt);
            ReflectionTestUtils.setField(ReflectionTestUtils.getField(line, "left"), "numberOfUpdates", leftUpdates);
            ReflectionTestUtils.setField(ReflectionTestUtils.getField(line, "right"), "numberOfUpdates", rightUpdates);
            return line;
        }
    }
}
