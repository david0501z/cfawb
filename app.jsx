import { useState } from 'react'
import './App.css'

const TabButton = ({
  isSelected,
  isFocused,
  onFocus,
  onKeyDown,
  onSelect,
  children,
}) => {
  return (
    <button
      className={`tab-button ${isSelected ? "selected" : ""}`}
      tabIndex={isFocused ? 0 : -1}
      onFocus={onFocus}
      onKeyDown={onKeyDown}
      onClick={onSelect}
    >
      {children}
    </button>
  )
}

const Tabs = ({
  label,
  children,
  defaultIndex = 0,
}) => {
  const [selectedIndex, setSelectedIndex] = useState(defaultIndex)
  const [focusedIndex, setFocusedIndex] = useState(defaultIndex)
  const [isOpen, setIsOpen] = useState(false)

  const tabElements = []
  const panelElements = []

  let index = 0
  children.forEach((child) => {
    if (child.type.name === "Tab") {
      tabElements.push({ ...child, index })
      index += 1
    } else if (child.type.name === "TabPanel") {
      panelElements.push(child)
    }
  })

  const selectedTab = tabElements[selectedIndex]

  const handleSelect = (newIndex) => {
    setSelectedIndex(newIndex)
    setFocusedIndex(newIndex)
    setIsOpen(false)
  }

  const handleKeyDown = (event) => {
    let newIndex = focusedIndex

    if (event.key === "ArrowLeft") {
      newIndex = Math.max(0, focusedIndex - 1)
    } else if (event.key === "ArrowRight") {
      newIndex = Math.min(tabElements.length - 1, focusedIndex + 1)
    } else if (event.key === "Home") {
      newIndex = 0
    } else if (event.key === "End") {
      newIndex = tabElements.length - 1
    } else if (event.key === "Enter" || event.key === " ") {
      handleSelect(focusedIndex)
      return
    } else if (event.key === "Escape") {
      setIsOpen(false)
      return
    }

    if (newIndex !== focusedIndex) {
      setFocusedIndex(newIndex)
      event.preventDefault()
    }
  }

  return (
    <div className="tabs">
      <div
        role="tablist"
        aria-label={label}
        className="tab-list"
      >
        <TabButton
          isSelected={true}
          isFocused={focusedIndex === selectedIndex}
          onFocus={() => setFocusedIndex(selectedIndex)}
          onKeyDown={handleKeyDown}
          onSelect={() => setIsOpen(!isOpen)}
        >
          {selectedTab?.props.children}
          <svg
            className={`arrow-icon ${isOpen ? "open" : ""}`}
            width="14"
            height="14"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <polyline points="6 9 12 15 18 9"></polyline>
          </svg>
        </TabButton>
        {isOpen && (
          <div className="tab-popover">
            {tabElements.map((tab, i) => (
              <TabButton
                key={i}
                isSelected={selectedIndex === i}
                isFocused={focusedIndex === i}
                onFocus={() => setFocusedIndex(i)}
                onKeyDown={handleKeyDown}
                onSelect={() => handleSelect(i)}
              >
                {tab.props.children}
              </TabButton>
            ))}
          </div>
        )}
      </div>

      <div className="tab-panels">
        {panelElements[selectedIndex]}
      </div>
    </div>
  )
}

const Tab = ({ children }) => {
  return <>{children}</>
}

const TabPanel = ({ children }) => {
  return (
    <div className="tab-panel" role="tabpanel">
      {children}
    </div>
  )
}

function App() {
  return (
    <div className="app">
      <Tabs label="示例标签页">
        <Tab>主页</Tab>
        <Tab>个人设置</Tab>
        <Tab>关于我们</Tab>

        <TabPanel>
          <h3>欢迎来到主页</h3>
          <p>这里是主页的内容，您可以在这里浏览最新信息。</p>
        </TabPanel>

        <TabPanel>
          <h3>个人设置</h3>
          <p>在这里您可以修改您的个人设置。</p>
        </TabPanel>

        <TabPanel>
          <h3>关于我们</h3>
          <p>了解更多关于我们的信息。</p>
        </TabPanel>
      </Tabs>
    </div>
  )
}

export default App
