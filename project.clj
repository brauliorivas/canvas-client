(defproject canvaslms "0.1.0-SNAPSHOT"
  :description "Interactive, confirmed grade transfers between Canvas instances"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.2"]
                 [clj-http "3.13.1"]
                 [cheshire "6.2.0"]
                 [com.github.seancorfield/next.jdbc "1.3.1118"]
                 [org.xerial/sqlite-jdbc "3.49.1.0"]
                 [org.clojure/tools.cli "1.1.230"]
                 [org.clj-commons/pretty "3.6.7"]
                 [org.jline/jline-reader "3.30.17"]
                 [org.jline/jline-terminal-jni "3.30.17"]
                 [io.github.cdimascio/dotenv-java "3.2.0"]
                 [org.slf4j/slf4j-nop "2.0.17"]]
  :main ^:skip-aot canvaslms.core
  :aliases {"cli" ["trampoline" "run"]}
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
