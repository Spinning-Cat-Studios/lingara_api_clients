# frozen_string_literal: true

require "json"
require "openssl"
require "socket"
require "minitest/autorun"
require "lingara"

# The tests raise into, and collect errors from, threads of their own; each
# asserts on Thread#value, so the default report is noise.
Thread.report_on_exception = false

# An in-process HTTP/1.1 fake on a TCPServer (WEBrick left the default gems
# in Ruby 3.0). Each connection is read as one request and handed, with a
# Conn to answer on, to the block the test supplies. Nothing here reaches a
# live endpoint.
class FakeServer
  Request = Struct.new(:method, :path, :headers, :body)

  # One accepted connection: helpers for the answers the tests need.
  class Conn
    attr_reader :socket

    def initialize(socket)
      @socket = socket
    end

    def json(status, value, headers = {})
      text(status, JSON.generate(value), {"Content-Type" => "application/json"}.merge(headers))
    end

    def text(status, body, headers = {})
      head = {"Content-Type" => "text/plain; charset=utf-8", "Content-Length" => body.bytesize.to_s, "Connection" => "close"}.merge(headers)
      write_head(status, head)
      @socket.write(body)
    end

    # Opens a chunked text/event-stream answer.
    def sse(headers = {})
      write_head(200, {"Content-Type" => "text/event-stream", "Transfer-Encoding" => "chunked"}.merge(headers))
    end

    def chunk(bytes)
      bytes = bytes.b
      @socket.write("#{bytes.bytesize.to_s(16)}\r\n#{bytes}\r\n")
      @socket.flush
    end

    def event(name, data)
      chunk("event: #{name}\ndata: #{JSON.generate(data)}\n\n")
    end

    def finish
      @socket.write("0\r\n\r\n")
      @socket.flush
    end

    # A TCP reset: SO_LINGER zero, then close.
    def reset
      @socket.setsockopt(Socket::SOL_SOCKET, Socket::SO_LINGER, [1, 0].pack("ii")) if @socket.is_a?(TCPSocket)
      @socket.close
    end

    # Blocks until the client closes its end, or +timeout+ seconds pass;
    # true when the close was seen.
    def closed_by_peer?(timeout)
      deadline = Process.clock_gettime(Process::CLOCK_MONOTONIC) + timeout
      loop do
        left = deadline - Process.clock_gettime(Process::CLOCK_MONOTONIC)
        return false if left <= 0
        return true if @socket.wait_readable(left) && @socket.read_nonblock(1024, exception: false).nil?
      end
    rescue IOError, SystemCallError
      true
    end

    def write_head(status, headers)
      lines = ["HTTP/1.1 #{status} #{status}"] + headers.map { |k, v| "#{k}: #{v}" }
      @socket.write(lines.join("\r\n") + "\r\n\r\n")
      @socket.flush
    end
  end

  attr_reader :url, :port

  # +tls+: an OpenSSL::SSL::SSLContext to serve HTTPS with.
  def initialize(tls: nil, &handler)
    @handler = handler
    @tcp = TCPServer.new("127.0.0.1", 0)
    @port = @tcp.addr[1]
    @listener = tls ? OpenSSL::SSL::SSLServer.new(@tcp, tls) : @tcp
    @url = "#{tls ? "https" : "http"}://127.0.0.1:#{@port}"
    @mutex = Mutex.new
    @requests = []
    @threads = []
    @acceptor = Thread.new { accept_loop }
    @acceptor.report_on_exception = false
  end

  def requests
    @mutex.synchronize { @requests.dup }
  end

  def token_url
    "#{@url}/oauth/token"
  end

  def close
    @acceptor.kill
    @tcp.close
    @mutex.synchronize { @threads.dup }.each { |t| t.join(2) || t.kill }
  end

  # A client pointed at this server with a virtual sleeper, plus +options+.
  def client(**options)
    Lingara::Client.new(base_url: @url, token_url: token_url, sleeper: ->(_) {}, **options)
  end

  private

  def accept_loop
    loop do
      socket = @listener.accept
      thread = Thread.new(socket) { |s| serve(s) }
      thread.report_on_exception = false
      @mutex.synchronize { @threads << thread }
    rescue OpenSSL::SSL::SSLError
      next
    end
  rescue IOError, SystemCallError
    nil
  end

  def serve(socket)
    request = read_request(socket)
    return unless request
    @mutex.synchronize { @requests << request }
    @handler.call(request, Conn.new(socket))
  rescue IOError, SystemCallError, OpenSSL::SSL::SSLError
    nil
  ensure
    begin
      socket.close
    rescue IOError, SystemCallError
      nil
    end
  end

  def read_request(socket)
    line = socket.gets("\r\n") or return nil
    method, path = line.split(" ")
    headers = {}
    while (header = socket.gets("\r\n")) && header != "\r\n"
      name, value = header.chomp.split(":", 2)
      headers[name.downcase] = value.strip
    end
    body = headers["content-length"] ? socket.read(headers["content-length"].to_i) : nil
    Request.new(method, path, headers, body)
  end
end

# Shared credentials and helpers for the tests.
module Fixtures
  CLIENT_ID = "lgr_cid_unit00000000000000000000"
  SECRET = "lgr_cs_unitsecret000000000000000000000000000000"

  module_function

  def token_response(conn, token = "lgr_at_unit", expires_in: 3600)
    conn.json(200, {access_token: token, token_type: "Bearer", expires_in: expires_in, scope: "usage:read"})
  end

  # A server answering the token endpoint with +token+ and everything else
  # with the block.
  def server(token: "lgr_at_unit", tls: nil, &handler)
    FakeServer.new(tls: tls) do |request, conn|
      if request.path == "/oauth/token"
        token_response(conn, token)
      else
        handler.call(request, conn)
      end
    end
  end

  def credentials
    {client_id: CLIENT_ID, client_secret: SECRET}
  end

  # A self-signed certificate for 127.0.0.1, minted per test run, and a
  # server context serving it.
  def tls_pair
    key = OpenSSL::PKey::RSA.new(2048)
    cert = OpenSSL::X509::Certificate.new
    cert.version = 2
    cert.serial = 1
    cert.subject = cert.issuer = OpenSSL::X509::Name.parse("/CN=127.0.0.1")
    cert.public_key = key.public_key
    cert.not_before = Time.now - 60
    cert.not_after = Time.now + 3600
    extensions = OpenSSL::X509::ExtensionFactory.new(cert, cert)
    cert.add_extension(extensions.create_extension("subjectAltName", "IP:127.0.0.1"))
    cert.add_extension(extensions.create_extension("basicConstraints", "CA:TRUE", true))
    cert.sign(key, OpenSSL::Digest.new("SHA256"))
    context = OpenSSL::SSL::SSLContext.new
    context.cert = cert
    context.key = key
    store = OpenSSL::X509::Store.new
    store.add_cert(cert)
    [context, store]
  end
end
