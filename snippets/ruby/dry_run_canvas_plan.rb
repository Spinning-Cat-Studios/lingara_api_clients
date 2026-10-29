# frozen_string_literal: true

module LingaraSnippets
  def self.dry_run_canvas_plan(client)
    # lingara:begin dryRunCanvasPlan
    # Judges a plan without applying it. A refused op is still a 200 answer,
    # ok false: branch on each verdict's code, and show its English error
    # only as a fallback.
    plan = {ops: [{handle: "c1", kind: "client", op: "create", body: {name: "Lesson server", scopes: ["events:read"]}}]}
    dry_run = client.canvases.dry_run_canvas_plan(plan).value
    dry_run.verdicts.each do |verdict|
      puts "#{verdict.handle}: #{verdict.verdict}#{" (#{verdict.code})" if verdict.code}"
    end
    puts(dry_run.ok ? "the plan would apply" : "the plan would not apply")
    # lingara:end
  end
end
