Embulk::JavaPlugin.register_output(
  "larges3", "org.embulk.output.Larges3FileOutputPlugin",
  File.expand_path('../../../../classpath', __FILE__))
