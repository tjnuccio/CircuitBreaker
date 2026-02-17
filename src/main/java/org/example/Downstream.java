package org.example;

import org.springframework.http.ResponseEntity;

public class Downstream {
    ResponseEntity<Response> create() {
        return ResponseEntity.ok(new Response("body", null));
    }
}
