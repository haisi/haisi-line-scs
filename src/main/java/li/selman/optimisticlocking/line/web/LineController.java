package li.selman.optimisticlocking.line.web;

import jakarta.annotation.Nullable;
import li.selman.optimisticlocking.line.Line;
import li.selman.optimisticlocking.line.LineId;
import li.selman.optimisticlocking.line.LineRepository;
import li.selman.optimisticlocking.line.LineService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("lines")
public class LineController {


    private final LineRepository lineRepository;
    private final LineService lineService;

    public LineController(LineRepository lineRepository, LineService lineService) {
        this.lineRepository = lineRepository;
        this.lineService = lineService;
    }

    @GetMapping("{id}")
    ResponseEntity<Line> get(@PathVariable UUID id) {
        return lineRepository.findById(new LineId(id))
                .map(it -> ResponseEntity.ok().eTag(it.getLockVersion()).body(it))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id,
                                @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable String ifMatch) {
        lineService.delete(new LineId(id), ifMatch);
        return ResponseEntity.noContent().build();
    }

}
