# frozen_string_literal: true

module LingaraSnippets
  def self.get_usage(client)
    # lingara:begin getUsage
    usage = client.get_usage.value
    usage.allowance.each { |row| puts "#{row.feature} (#{row.window}): #{row.remaining} of #{row.limit} left" }
    puts "#{usage.ledger.calls} calls this month" if usage.ledger
    # lingara:end
  end
end
